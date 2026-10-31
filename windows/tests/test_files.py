"""The guard rails on deletion are the part that must not regress."""

from pathlib import Path

import pytest

from jarvis_win.core.files import (
    MAX_FILES,
    validate_root,
    containing_root,
    Candidate,
    DeletionPlan,
    DeletionRefused,
    build_plan,
    check_path,
    default_allowed_roots,
    execute,
)


@pytest.fixture
def sandbox(tmp_path):
    """An allowed root with a few files, plus a forbidden area beside it."""
    allowed = tmp_path / "Documents"
    allowed.mkdir()
    (allowed / "note.txt").write_text("hello", encoding="utf-8")
    (allowed / "report.pdf").write_bytes(b"x" * 1024)
    (allowed / "صورة.png").write_bytes(b"y" * 512)

    outside = tmp_path / "Windows"
    outside.mkdir()
    (outside / "system.dll").write_bytes(b"z" * 10)

    return tmp_path, [allowed]


class TestScoping:
    def test_file_inside_an_allowed_root_is_accepted(self, sandbox):
        tmp, roots = sandbox
        assert check_path(roots[0] / "note.txt", roots) is None

    def test_file_outside_every_root_is_refused(self, sandbox):
        tmp, roots = sandbox
        reason = check_path(tmp / "Windows" / "system.dll", roots)
        assert reason is not None

    def test_blocked_directory_name_is_refused_even_if_reachable(self, sandbox):
        tmp, roots = sandbox
        target = roots[0] / "node_modules"
        target.mkdir()
        assert "محمي" in (check_path(target / "pkg.json", roots) or "")

    def test_drive_root_is_refused(self, sandbox):
        _, roots = sandbox
        assert check_path(Path(Path.cwd().anchor or "/"), roots) is not None

    def test_a_root_under_a_blocked_ancestor_still_works(self, tmp_path):
        # On Windows a temp or portable folder legitimately lives under
        # AppData. Refusing the whole subtree made nothing deletable.
        root = tmp_path / "AppData" / "Local" / "Temp" / "Documents"
        root.mkdir(parents=True)
        (root / "note.txt").write_text("x", encoding="utf-8")
        assert check_path(root / "note.txt", [root]) is None

    def test_blocked_name_below_the_root_is_still_refused(self, tmp_path):
        root = tmp_path / "Documents"
        (root / ".git").mkdir(parents=True)
        (root / ".git" / "HEAD").write_text("x", encoding="utf-8")
        assert "محمي" in (check_path(root / ".git" / "HEAD", [root]) or "")

    def test_containing_root_is_reported(self, sandbox):
        _, roots = sandbox
        assert containing_root(roots[0] / "note.txt", roots) == roots[0]

    def test_containing_root_is_none_outside(self, sandbox):
        tmp, roots = sandbox
        assert containing_root(tmp / "Windows" / "system.dll", roots) is None

    def test_no_roots_means_nothing_is_deletable(self):
        assert check_path(Path("/tmp/x"), []) is not None

    def test_sensitive_extension_is_refused(self, sandbox):
        _, roots = sandbox
        (roots[0] / "tool.exe").write_bytes(b"MZ")
        assert ".exe" in (check_path(roots[0] / "tool.exe", roots) or "")

    @pytest.mark.skipif(not hasattr(Path, "symlink_to"), reason="no symlink support")
    def test_symlink_escaping_the_root_is_refused(self, sandbox):
        tmp, roots = sandbox
        secret = tmp / "Windows" / "system.dll"
        link = roots[0] / "innocent.txt"
        try:
            link.symlink_to(secret)
        except (OSError, NotImplementedError):
            pytest.skip("symlinks not permitted here")
        # Resolving the link lands outside the root, so it must not pass.
        assert check_path(link, roots) is not None


class TestPlanning:
    def test_plan_lists_matching_files(self, sandbox):
        _, roots = sandbox
        plan = build_plan(["*.txt"], roots=roots)
        assert [c.path.name for c in plan.candidates] == ["note.txt"]

    def test_plan_does_not_delete_anything(self, sandbox):
        _, roots = sandbox
        build_plan(["*"], roots=roots)
        assert (roots[0] / "note.txt").exists()

    def test_arabic_filename_matches(self, sandbox):
        _, roots = sandbox
        plan = build_plan(["*.png"], roots=roots)
        assert plan.candidates[0].path.name == "صورة.png"

    def test_sweeping_pattern_is_refused_outright(self, sandbox):
        _, roots = sandbox
        plan = build_plan(["*"], roots=roots)
        assert plan.is_empty
        assert any("عام جدًا" in reason for _, reason in plan.skipped)

    def test_directories_need_an_explicit_request(self, sandbox):
        _, roots = sandbox
        (roots[0] / "folder").mkdir()
        plan = build_plan(["folder"], roots=roots)
        assert plan.is_empty
        assert any("مجلد" in reason for _, reason in plan.skipped)

    def test_directory_included_when_asked(self, sandbox):
        _, roots = sandbox
        (roots[0] / "folder").mkdir()
        (roots[0] / "folder" / "inner.txt").write_text("x", encoding="utf-8")
        plan = build_plan(["folder"], roots=roots, include_dirs=True)
        assert len(plan.candidates) == 1
        assert plan.candidates[0].is_dir

    def test_outside_paths_are_reported_not_silently_dropped(self, sandbox):
        tmp, roots = sandbox
        plan = build_plan([str(tmp / "Windows" / "system.dll")], roots=roots)
        assert plan.is_empty
        assert plan.skipped  # the user is told why

    def test_missing_file_is_reported(self, sandbox):
        _, roots = sandbox
        plan = build_plan([str(roots[0] / "ghost.txt")], roots=roots)
        assert any("غير موجود" in reason for _, reason in plan.skipped)

    def test_duplicate_patterns_do_not_double_count(self, sandbox):
        _, roots = sandbox
        plan = build_plan(["*.txt", "note.txt"], roots=roots)
        assert len(plan.candidates) == 1

    def test_too_many_files_is_refused(self, sandbox):
        _, roots = sandbox
        for i in range(MAX_FILES + 5):
            (roots[0] / f"bulk{i}.log").write_text("x", encoding="utf-8")
        with pytest.raises(DeletionRefused, match="يتجاوز"):
            build_plan(["*.log"], roots=roots)


class TestSummary:
    def test_summary_says_trash_by_default(self, sandbox):
        _, roots = sandbox
        plan = build_plan(["*.txt"], roots=roots)
        assert "سلّة" in plan.summary_ar()

    def test_summary_says_permanent_when_asked(self, sandbox):
        _, roots = sandbox
        plan = build_plan(["*.txt"], roots=roots, permanent=True)
        assert "نهائيًا" in plan.summary_ar()

    def test_empty_plan_says_so(self):
        assert "لا توجد" in DeletionPlan().summary_ar()

    def test_summary_reports_the_count(self, sandbox):
        _, roots = sandbox
        plan = build_plan(["*.txt", "*.pdf"], roots=roots)
        assert "2" in plan.summary_ar()


class TestExecution:
    def test_permanent_delete_removes_the_file(self, sandbox):
        _, roots = sandbox
        target = roots[0] / "note.txt"
        plan = build_plan(["note.txt"], roots=roots, permanent=True)
        result = execute(plan)
        assert result.ok
        assert not target.exists()

    def test_execute_only_touches_planned_paths(self, sandbox):
        _, roots = sandbox
        plan = build_plan(["note.txt"], roots=roots, permanent=True)
        execute(plan)
        assert (roots[0] / "report.pdf").exists()

    def test_empty_plan_is_a_no_op(self, sandbox):
        result = execute(DeletionPlan())
        assert result.deleted == []
        assert result.failed == []

    def test_non_permanent_delete_refuses_without_send2trash(self, sandbox, monkeypatch):
        # Falling back to a permanent delete here would turn a recoverable
        # mistake into an unrecoverable one.
        import builtins

        real_import = builtins.__import__

        def no_send2trash(name, *args, **kwargs):
            if name == "send2trash":
                raise ImportError("not installed")
            return real_import(name, *args, **kwargs)

        monkeypatch.setattr(builtins, "__import__", no_send2trash)
        _, roots = sandbox
        plan = build_plan(["note.txt"], roots=roots, permanent=False)
        with pytest.raises(DeletionRefused):
            execute(plan)
        assert (roots[0] / "note.txt").exists()

    def test_audit_records_every_deletion(self, sandbox, tmp_path):
        _, roots = sandbox
        audit = tmp_path / "audit" / "deletions.tsv"
        plan = build_plan(["note.txt"], roots=roots, permanent=True)
        execute(plan, audit_path=audit)
        logged = audit.read_text(encoding="utf-8")
        assert "deleted" in logged
        assert "note.txt" in logged

    def test_failure_is_reported_not_swallowed(self, sandbox):
        _, roots = sandbox
        plan = DeletionPlan(
            candidates=[Candidate(roots[0] / "ghost.txt", 0, False)],
            permanent=True,
        )
        result = execute(plan)
        assert not result.ok
        assert result.failed


class TestRootPolicy:
    def test_a_system_folder_cannot_be_made_a_root(self, tmp_path):
        root = tmp_path / "Windows" / "System32"
        root.mkdir(parents=True)
        assert validate_root(root) is not None

    def test_drive_root_cannot_be_made_a_root(self):
        assert validate_root(Path(Path.cwd().anchor or "/")) is not None

    def test_an_ordinary_folder_is_accepted(self, tmp_path):
        root = tmp_path / "Documents"
        root.mkdir()
        assert validate_root(root) is None


class TestDefaults:
    def test_default_roots_are_all_under_home(self, tmp_path):
        for name in ("Documents", "Downloads"):
            (tmp_path / name).mkdir()
        roots = default_allowed_roots(home=tmp_path)
        assert roots
        for root in roots:
            assert str(root).startswith(str(tmp_path))

    def test_absent_folders_are_not_offered(self, tmp_path):
        assert default_allowed_roots(home=tmp_path) == []
