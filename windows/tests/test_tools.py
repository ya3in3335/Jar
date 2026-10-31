import pytest

from jarvis_win.core import files
from jarvis_win.core.tools import DELETE_FILES, ToolRegistry, default_tools


@pytest.fixture
def registry():
    return ToolRegistry()


class TestSchemas:
    def test_every_tool_exposes_a_function_schema(self, registry):
        for schema in registry.schemas():
            assert schema["type"] == "function"
            assert schema["function"]["name"]
            assert schema["function"]["parameters"]["type"] == "object"

    def test_schemas_forbid_extra_properties(self, registry):
        for schema in registry.schemas():
            assert schema["function"]["parameters"]["additionalProperties"] is False

    def test_optional_params_are_not_required(self):
        schema = DELETE_FILES.schema()["function"]["parameters"]
        assert schema["required"] == ["patterns"]

    def test_enum_reaches_the_schema(self, registry):
        app_tool = registry.find("open_application")
        properties = app_tool.schema()["function"]["parameters"]["properties"]
        assert "notepad" in properties["app"]["enum"]

    def test_tool_names_are_unique(self, registry):
        assert len(registry.names) == len(set(registry.names))


class TestValidation:
    def test_missing_required_argument_is_rejected(self):
        assert "patterns" in (DELETE_FILES.validate({}) or "")

    def test_unknown_argument_is_rejected(self):
        problem = DELETE_FILES.validate({"patterns": "*.txt", "force": True})
        assert "force" in (problem or "")

    def test_wrong_type_is_rejected(self):
        problem = DELETE_FILES.validate({"patterns": "*.txt", "permanent": "نعم"})
        assert "صح/خطأ" in (problem or "")

    def test_valid_arguments_pass(self):
        assert DELETE_FILES.validate({"patterns": "*.txt", "permanent": False}) is None

    def test_enum_violation_is_rejected(self, registry):
        app_tool = registry.find("open_application")
        assert app_tool.validate({"app": "doom"}) is not None


class TestDeletionIsAlwaysSensitive:
    def test_delete_is_flagged_sensitive(self):
        assert DELETE_FILES.sensitive

    def test_no_other_tool_is_sensitive(self):
        sensitive = [t.name for t in default_tools() if t.sensitive]
        assert sensitive == ["delete_files"]


class TestDeletionNeverActsOnFirstCall:
    """The tool must plan and wait, never delete in one step."""

    @pytest.fixture
    def sandbox(self, tmp_path, monkeypatch):
        allowed = tmp_path / "Documents"
        allowed.mkdir()
        (allowed / "a.txt").write_text("x", encoding="utf-8")
        (allowed / "b.txt").write_text("y", encoding="utf-8")
        monkeypatch.setattr(files, "default_allowed_roots", lambda home=None: [allowed])
        return allowed

    def test_first_call_returns_a_plan_and_deletes_nothing(self, sandbox):
        result = DELETE_FILES.run({"patterns": "*.txt"})
        assert result.pending is not None
        assert not result.ok
        assert (sandbox / "a.txt").exists()
        assert (sandbox / "b.txt").exists()

    def test_plan_lists_the_files_by_name(self, sandbox):
        result = DELETE_FILES.run({"patterns": "*.txt"})
        assert "a.txt" in result.summary
        assert "b.txt" in result.summary

    def test_summary_states_where_they_go(self, sandbox):
        assert "سلّة" in DELETE_FILES.run({"patterns": "*.txt"}).summary

    def test_permanent_request_is_stated_in_the_summary(self, sandbox):
        result = DELETE_FILES.run({"patterns": "*.txt", "permanent": True})
        assert "نهائيًا" in result.summary

    def test_no_match_reports_why(self, sandbox):
        result = DELETE_FILES.run({"patterns": "*.iso"})
        assert not result.ok
        assert result.pending is None

    def test_sweeping_pattern_is_refused(self, sandbox):
        result = DELETE_FILES.run({"patterns": "*"})
        assert result.pending is None
        assert not result.ok

    def test_path_outside_the_allowed_roots_is_refused(self, sandbox, tmp_path):
        outside = tmp_path / "elsewhere.txt"
        outside.write_text("x", encoding="utf-8")
        result = DELETE_FILES.run({"patterns": str(outside)})
        assert result.pending is None
        assert outside.exists()

    def test_approving_the_plan_is_what_deletes(self, sandbox):
        result = DELETE_FILES.run({"patterns": "a.txt", "permanent": True})
        plan = result.pending
        assert (sandbox / "a.txt").exists()  # still there before approval

        outcome = files.execute(plan)
        assert outcome.ok
        assert not (sandbox / "a.txt").exists()
        assert (sandbox / "b.txt").exists()

    def test_a_list_of_patterns_is_accepted(self, sandbox):
        result = DELETE_FILES.run({"patterns": ["a.txt", "b.txt"]})
        assert result.pending is not None
        assert len(result.pending.candidates) == 2

    def test_missing_patterns_argument_fails_cleanly(self):
        result = DELETE_FILES.run({})
        assert not result.ok
        assert result.pending is None


class TestRegistry:
    def test_lookup_by_name(self, registry):
        assert registry.find("delete_files") is DELETE_FILES

    def test_unknown_tool_is_none(self, registry):
        assert registry.find("format_drive") is None
