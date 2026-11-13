import * as THREE from 'three';
import {OrbitControls} from 'three/addons/controls/OrbitControls.js';
import {RoomEnvironment} from 'three/addons/environments/RoomEnvironment.js';
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';

const EMBED=/embed/.test(location.hash)||!!window.JARVIS_EMBED;
if(EMBED){document.documentElement.classList.add('embed');document.body.classList.add('embed');}
const damp=(c,t,l,dt)=>c+(t-c)*(1-Math.exp(-l*dt)), clamp=(v,a,b)=>Math.min(b,Math.max(a,v));
const $=id=>document.getElementById(id);

/* ===================== STATES (same names as the app's AssistantState) ===================== */
const ST={
  idle:{ar:'جاهز',ic:'🟢',c:'#4fd8ff'}, listening:{ar:'ينصت',ic:'🎙️',c:'#35e0a1'},
  understanding:{ar:'يفهم',ic:'🧩',c:'#7cc4ff'}, thinking:{ar:'يفكّر',ic:'🧠',c:'#a78bfa'},
  speaking:{ar:'يتكلّم',ic:'🗣️',c:'#4fd8ff'}, executing:{ar:'ينفّذ',ic:'⚙️',c:'#ffb347'},
  success:{ar:'تمّ',ic:'✅',c:'#34d399'}, error:{ar:'خطأ',ic:'⚠️',c:'#ff5c7a'},
  disconnected:{ar:'غير متصل',ic:'🔌',c:'#6b7a8c'}};
let state='idle', level=0, levelAt=-1e9;

/* ===================== RENDERER ===================== */
const renderer=new THREE.WebGLRenderer({antialias:true,alpha:true,powerPreference:'high-performance'});
renderer.setPixelRatio(Math.min(devicePixelRatio,2)); renderer.setSize(innerWidth,innerHeight);
renderer.setClearColor(0x000000,0);
renderer.toneMapping=THREE.ACESFilmicToneMapping; renderer.toneMappingExposure=1.15;
$('app').appendChild(renderer.domElement);
const scene=new THREE.Scene();
const camera=new THREE.PerspectiveCamera(27,innerWidth/innerHeight,.05,50);
const controls=new OrbitControls(camera,renderer.domElement);
controls.enableZoom=false; controls.enablePan=false; controls.enableDamping=true; controls.dampingFactor=.08;
controls.minAzimuthAngle=-.6; controls.maxAzimuthAngle=.6; controls.minPolarAngle=1.25; controls.maxPolarAngle=1.72;
const pm=new THREE.PMREMGenerator(renderer); scene.environment=pm.fromScene(new RoomEnvironment(),.05).texture; scene.environmentIntensity=.55;
scene.add(new THREE.HemisphereLight('#d7ebff','#101826',.55));
const keyL=new THREE.DirectionalLight('#fff3e2',2.4); keyL.position.set(1.2,2.6,2.4); scene.add(keyL);
const rimL=new THREE.PointLight('#4fd8ff',7,3,2), rimR=new THREE.PointLight('#4fd8ff',5,3,2); scene.add(rimL,rimR);
const acc=new THREE.Color('#4fd8ff'), accT=new THREE.Color('#4fd8ff');

/* ===================== HOLOGRAM HUD BEHIND THE HEAD ===================== */
const hud=new THREE.Group(); scene.add(hud);
const hudMat=new THREE.MeshBasicMaterial({color:acc,transparent:true,opacity:.55,side:THREE.DoubleSide,blending:THREE.AdditiveBlending,depthWrite:false});
const arcs=[[.19,.194,6.283],[.212,.222,4.4],[.243,.246,5.2],[.268,.283,2.2],[.305,.308,6.283],[.33,.337,1.3]].map(([a,b,len],i)=>{
  const m=new THREE.Mesh(new THREE.RingGeometry(a,b,128,1,i*1.3,len),hudMat); hud.add(m); return m;});
{const n=72,p=new Float32Array(n*3);for(let i=0;i<n;i++){const a=i/n*6.283;p[i*3]=Math.cos(a)*.256;p[i*3+1]=Math.sin(a)*.256;}
 const g=new THREE.BufferGeometry();g.setAttribute('position',new THREE.BufferAttribute(p,3));
 hud.add(new THREE.Points(g,new THREE.PointsMaterial({color:acc,size:.006,transparent:true,opacity:.9,blending:THREE.AdditiveBlending,depthWrite:false})));}
const gc=document.createElement('canvas');gc.width=gc.height=128;{const x=gc.getContext('2d'),g=x.createRadialGradient(64,64,0,64,64,64);
  g.addColorStop(0,'rgba(255,255,255,.75)');g.addColorStop(.35,'rgba(255,255,255,.22)');g.addColorStop(1,'rgba(255,255,255,0)');x.fillStyle=g;x.fillRect(0,0,128,128);}
const glow=new THREE.Sprite(new THREE.SpriteMaterial({map:new THREE.CanvasTexture(gc),color:acc,transparent:true,opacity:.5,blending:THREE.AdditiveBlending,depthWrite:false}));
glow.scale.setScalar(.85); glow.position.z=-.02; hud.add(glow);
// particles dissolving away under the neck
const PN=220, pPos=new Float32Array(PN*3), pSeed=new Float32Array(PN);
for(let i=0;i<PN;i++)pSeed[i]=Math.random();
const pGeo=new THREE.BufferGeometry(); pGeo.setAttribute('position',new THREE.BufferAttribute(pPos,3));
const motes=new THREE.Points(pGeo,new THREE.PointsMaterial({color:acc,size:.0075,transparent:true,opacity:.85,blending:THREE.AdditiveBlending,depthWrite:false}));
motes.frustumCulled=false; scene.add(motes);

/* ===================== NECK FADE (dithered dissolve, shared uniforms) ===================== */
const FU={uNeck:{value:1.45},uLen:{value:.125},uCol:{value:acc},uGray:{value:0}};
function fadeMat(m){
  m.onBeforeCompile=sh=>{Object.assign(sh.uniforms,FU);
    sh.vertexShader='varying float vWY;\n'+sh.vertexShader.replace('#include <project_vertex>','#include <project_vertex>\n vWY=(modelMatrix*vec4(transformed,1.0)).y;');
    sh.fragmentShader='varying float vWY;uniform float uNeck,uLen,uGray;uniform vec3 uCol;\n'+sh.fragmentShader.replace('#include <dithering_fragment>',
      `#include <dithering_fragment>
       float jf=smoothstep(uNeck-uLen,uNeck,vWY);
       float jn=fract(sin(dot(gl_FragCoord.xy,vec2(12.9898,78.233)))*43758.5453);
       if(jf<=jn*.999+.0005) discard;
       gl_FragColor.rgb=mix(gl_FragColor.rgb,vec3(dot(gl_FragColor.rgb,vec3(.3,.59,.11))),uGray);
       gl_FragColor.rgb+=uCol*pow(1.-jf,1.3)*.6;`);};
  m.needsUpdate=true;}

/* ===================== MODEL + RETARGETED CLIPS ===================== */
try{self.createImageBitmap=undefined;}catch(e){}
const loader=new GLTFLoader();
const bkey=n=>n.replace(/^mixamorig:?/,''), isB=o=>o.isBone||/^mixamorig/.test(o.name);
const loadGLB=f=>new Promise((res,rej)=>loader.load(f,g=>{prep(g);res(g);},undefined,rej));
const _v=new THREE.Vector3();
function prep(g){
  const root=g.scene,bones={};
  root.traverse(o=>{if(isB(o))bones[bkey(o.name)]=o;if(o.isMesh){o.frustumCulled=false;}});
  root.updateMatrixWorld(true);
  const top=(bones.HeadTop_End||bones.Head).getWorldPosition(_v).y;let bot=1e9;
  ['LeftToeBase','RightToeBase','LeftFoot','RightFoot'].forEach(k=>{if(bones[k])bot=Math.min(bot,bones[k].getWorldPosition(_v).y);});
  const s=1.72/Math.max(top-bot,1e-6),wrap=new THREE.Group();root.scale.multiplyScalar(s);root.position.y-=bot*s;wrap.add(root);
  if(bones.LeftUpLeg.getWorldPosition(_v).x<bones.RightUpLeg.getWorldPosition(new THREE.Vector3()).x)root.rotation.y+=Math.PI;
  const rest={};for(const k in bones)rest[k]={q:bones[k].quaternion.clone(),p:bones[k].position.clone()};
  wrap.updateMatrixWorld(true);
  const order=[],refW={},statQ={},refDir={};
  root.traverse(o=>{if(!isB(o))return;const k=bkey(o.name);if(bones[k]!==o)return;order.push(k);
    refW[k]=o.getWorldQuaternion(new THREE.Quaternion());
    if(!isB(o.parent)||bones[bkey(o.parent.name)]!==o.parent)statQ[k]=o.parent.getWorldQuaternion(new THREE.Quaternion());});
  for(const k of order){const kids=bones[k].children.filter(isB);
    const c=kids.length===1?kids[0]:(/Hand$/.test(k)?kids.find(x=>/Middle1$/.test(x.name)):null);
    if(c){const d=c.getWorldPosition(new THREE.Vector3()).sub(bones[k].getWorldPosition(new THREE.Vector3()));if(d.length()>1e-5)refDir[k]=d.normalize();}}
  g.userData={wrap,bones,rest,order,refW,statQ,refDir,hipsRefW:bones.Hips.getWorldPosition(new THREE.Vector3()),
    hpInv:bones.Hips.parent.matrixWorld.clone().invert(),mixer:new THREE.AnimationMixer(root)};
}
function bake(g,clip){
  const U=g.userData,B=U.bones,act=U.mixer.clipAction(clip);act.play();
  const n=Math.max(2,Math.ceil(clip.duration*30)+1),times=new Float32Array(n),D={},P=new Float32Array(n*3),q=new THREE.Quaternion(),qi=new THREE.Quaternion(),v=new THREE.Vector3();
  for(const k in B)D[k]=new Float32Array(n*4);
  for(let i=0;i<n;i++){times[i]=i*clip.duration/(n-1);U.mixer.setTime(Math.min(times[i],clip.duration-1e-4));U.wrap.updateMatrixWorld(true);
    for(const k in B)B[k].getWorldQuaternion(q).multiply(qi.copy(U.refW[k]).invert()).toArray(D[k],i*4);
    B.Hips.getWorldPosition(v).sub(U.hipsRefW).toArray(P,i*3);}
  act.stop();U.mixer.uncacheClip(clip);
  return{times,D,P,duration:clip.duration,dir:U.refDir};
}
function makeClip(name,L,g){
  const U=g.userData,B=U.bones,n=L.times.length,out={},W={},C={},pos=new Float32Array(n*3),qi=new THREE.Quaternion(),ql=new THREE.Quaternion(),v=new THREE.Vector3();
  for(const k of U.order){W[k]=new THREE.Quaternion();if(L.D[k])out[k]=new Float32Array(n*4);C[k]=U.refW[k].clone();
    if(L.dir[k]&&U.refDir[k])C[k].premultiply(ql.setFromUnitVectors(U.refDir[k],L.dir[k]));}
  for(let i=0;i<n;i++){
    for(const k of U.order){const pW=U.statQ[k]||W[bkey(B[k].parent.name)];
      if(L.D[k])W[k].fromArray(L.D[k],i*4).multiply(C[k]);else W[k].copy(pW).multiply(U.rest[k].q);
      if(out[k])ql.copy(qi.copy(pW).invert()).multiply(W[k]).normalize().toArray(out[k],i*4);}
    v.fromArray(L.P,i*3).add(U.hipsRefW).applyMatrix4(U.hpInv).toArray(pos,i*3);}
  const tracks=[new THREE.VectorKeyframeTrack(B.Hips.name+'.position',L.times,pos)];
  for(const k in out)tracks.push(new THREE.QuaternionKeyframeTrack(B[k].name+'.quaternion',L.times,out[k]));
  return new THREE.AnimationClip(name,L.duration,tracks);
}

let G=null,B=null,mixer=null,ACT={},cur=null,faces=[];
function fadeTo(name,once){
  const a=ACT[name];if(!a||a===cur)return;
  a.reset();a.setLoop(once?THREE.LoopOnce:THREE.LoopRepeat,once?1:Infinity);a.clampWhenFinished=!!once;a.play();
  if(cur)cur.crossFadeTo(a,.35,false);cur=a;
}

/* ===================== PUBLIC API ===================== */
let nodUntil=0, smileUntil=0, T=0;
function setState(s){
  s=String(s||'').toLowerCase(); if(!ST[s]||s===state&&s!=='success'&&s!=='error')return;
  state=s; accT.set(ST[s].c);
  document.documentElement.style.setProperty('--acc',ST[s].c); $('stt').textContent=ST[s].ar;
  [...$('dock').children].forEach(b=>b.classList.toggle('on',b.dataset.s===s));
  if(s==='success'){fadeTo('agree',true);smileUntil=T+2.2;}
  else if(s==='error')fadeTo('headShake',true);
  else fadeTo('idle');
  if(!EMBED){const say=$('say');
    const txt={speaking:'مرحبًا، أنا جارفيس. كيف أستطيع مساعدتك؟',listening:'أنا أسمعك…',thinking:'لحظة، أفكّر في ذلك…',executing:'جاري تنفيذ الأمر…',
      success:'تمّ بنجاح.',error:'عذرًا، حدث خطأ.',disconnected:'انقطع الاتصال.',understanding:'أحلّل ما قلته…'}[s];
    if(txt){say.textContent=txt;say.classList.remove('off');}else say.classList.add('off');}
}
function setLevel(v){level=clamp(+v||0,0,1);levelAt=performance.now();}
window.Jarvis={setState,setLevel,states:Object.keys(ST),get state(){return state;}};
addEventListener('message',e=>{const d=e.data;if(!d||d.type!=='jarvis')return;if(d.state)setState(d.state);if(d.level!=null)setLevel(d.level);});

/* ===================== INPUT ===================== */
let px=0,py=0,pAt=-1e9,down=null;
const cv=renderer.domElement;
addEventListener('pointermove',e=>{px=(e.clientX/innerWidth)*2-1;py=(e.clientY/innerHeight)*2-1;pAt=performance.now();});
cv.addEventListener('pointerdown',e=>{down={x:e.clientX,y:e.clientY,t:performance.now()};px=(e.clientX/innerWidth)*2-1;py=(e.clientY/innerHeight)*2-1;pAt=down.t;});
cv.addEventListener('pointerup',e=>{if(down&&performance.now()-down.t<320&&Math.hypot(e.clientX-down.x,e.clientY-down.y)<10){ // tap = greet
  smileUntil=T+1.8;nodUntil=T+.9;try{window.JarvisHost&&window.JarvisHost.onTap&&window.JarvisHost.onTap();}catch(_){} }down=null;});

/* ===================== LOOP ===================== */
const _e=new THREE.Euler(),_q=new THREE.Quaternion(),hp=new THREE.Vector3(),np=new THREE.Vector3();
const add=(k,x,y,z)=>{const b=B[k];if(!b)return;_e.set(x,y,z);_q.setFromEuler(_e);b.quaternion.multiply(_q);};
const S={pitch:0,yaw:0,roll:0,ex:0,ey:0,mouth:0,smile:0,lv:0,gray:0,hudS:1,camZ:1};
let last=performance.now(),first=true;
function camDist(){const a=innerWidth/innerHeight;return Math.max(1.22,1.06/a)*(EMBED?.9:1);}
function tick(now){
  requestAnimationFrame(tick);
  const dt=Math.min((now-last)/1000,.05);last=now;T+=dt;
  if(!G){renderer.render(scene,camera);return;}
  // audio level: real one from the host if fresh, otherwise a synthetic envelope so the demo is alive
  const ext=now-levelAt<350;
  let lv=ext?level:0;
  if(!ext&&state==='speaking')lv=clamp(.35+.45*Math.sin(T*9.3)*Math.sin(T*3.1+1)+.25*Math.sin(T*17.7),0,1);
  if(!ext&&state==='listening')lv=.25+.2*Math.sin(T*5.2)*Math.sin(T*1.7);
  S.lv=damp(S.lv,lv,18,dt);
  // look target: follow the finger/mouse for a while, then wander
  const fresh=now-pAt<3500; let ty=0,tp=0,tr=0,tex=0,tey=0;
  if(fresh){ty=px*.5;tp=py*.3;tex=px*.3;tey=py*.2;}else{ty=Math.sin(T*.31)*.14+Math.sin(T*.13)*.08;tp=Math.sin(T*.23)*.05;tex=Math.sin(T*.7)*.12;}
  if(state==='listening'){tr+=.11;tp+=.07;}
  if(state==='understanding'){tr-=.06;tp+=.03;}
  if(state==='thinking'){ty=.26+Math.sin(T*.8)*.07;tp=-.17;tex=.3;tey=-.3;tr=-.05;}
  if(state==='executing'){tex=Math.sin(T*6.5)*.3;tp+=.05;}
  if(state==='speaking'){tp+=S.lv*.05*Math.sin(T*7);ty+=Math.sin(T*1.3)*.05;}
  if(state==='disconnected'){ty=0;tp=.26;tex=0;tey=.2;tr=.03;}
  if(T<nodUntil)tp+=.16*Math.sin((nodUntil-T)/.9*Math.PI*2);
  S.yaw=damp(S.yaw,ty,5,dt);S.pitch=damp(S.pitch,tp,5,dt);S.roll=damp(S.roll,tr,4,dt);
  S.ex=damp(S.ex,tex,9,dt);S.ey=damp(S.ey,tey,9,dt);
  S.mouth=damp(S.mouth,state==='speaking'?clamp(S.lv*1.25,0,1):0,22,dt);
  S.smile=damp(S.smile,(T<smileUntil||state==='success')?1:(state==='idle'||state==='listening'?.18:0),5,dt);
  S.gray=damp(S.gray,state==='disconnected'?1:0,4,dt);

  for(const k in B)B[k].quaternion.copy(G.userData.rest[k].q);
  mixer.update(dt);
  add('Spine2',S.pitch*.15,S.yaw*.2,0);add('Neck',S.pitch*.35,S.yaw*.35,S.roll*.4);add('Head',S.pitch*.6,S.yaw*.55,S.roll*.6);
  add('LeftEye',S.ey,S.ex,0);add('RightEye',S.ey,S.ex,0);
  for(const f of faces){const d=f.morphTargetDictionary,inf=f.morphTargetInfluences;
    if(d.mouthOpen!=null)inf[d.mouthOpen]=S.mouth*.85;if(d.mouthSmile!=null)inf[d.mouthSmile]=S.smile*.75;}
  G.userData.wrap.updateMatrixWorld(true);
  B.Head.getWorldPosition(hp);B.Neck.getWorldPosition(np);
  FU.uNeck.value=damp(FU.uNeck.value,np.y+.012,first?999:10,dt);FU.uGray.value=S.gray;
  // colours follow the state
  acc.lerp(accT,1-Math.exp(-5*dt));rimL.color.copy(acc);rimR.color.copy(acc);
  const pulse=state==='thinking'||state==='understanding'?.5+.5*Math.sin(T*3):state==='executing'?.5+.5*Math.sin(T*8):.6;
  rimL.intensity=(5+S.lv*9+pulse*3)*(1-S.gray*.7);rimR.intensity=rimL.intensity*.7;
  rimL.position.set(hp.x-.55,hp.y+.2,hp.z-.25);rimR.position.set(hp.x+.55,hp.y+.05,hp.z-.3);
  // HUD rings behind the head
  const cy=hp.y+.085;
  hud.position.set(hp.x*.6,cy,hp.z-.26);hud.quaternion.copy(camera.quaternion);
  const spin=state==='thinking'||state==='executing'?2.6:state==='disconnected'?.15:1;
  arcs.forEach((m,i)=>{m.rotation.z+=dt*spin*(i%2?-.45:.3)*(1+i*.18);});
  S.hudS=damp(S.hudS,1+S.lv*.16,14,dt);hud.scale.setScalar(S.hudS);
  hudMat.opacity=(.3+S.lv*.5+pulse*.12)*(1-S.gray*.6);glow.material.opacity=(.32+S.lv*.4)*(1-S.gray*.7);
  // dissolving motes
  for(let i=0;i<PN;i++){const s=pSeed[i],ph=(T*(.12+s*.16)+s*7.3)%1,a=s*61.7+T*.2*(s-.5),r=.05+((s*13.37)%1)*.2;
    pPos[i*3]=np.x+Math.cos(a)*r;pPos[i*3+1]=np.y+.01-ph*.3;pPos[i*3+2]=np.z+Math.sin(a)*r*.7;}
  pGeo.attributes.position.needsUpdate=true;motes.material.opacity=.75*(1-S.gray*.6);
  // camera keeps the head framed (slightly bigger while speaking / listening)
  S.camZ=damp(S.camZ,state==='speaking'||state==='listening'?.93:1,3,dt);
  const tgt=_v.set(hp.x*.5,cy-.015,hp.z);
  if(first){controls.target.copy(tgt);camera.position.set(tgt.x,tgt.y+.02,tgt.z+camDist());first=false;}
  else{const d=camera.position.clone().sub(controls.target).setLength(camDist()*S.camZ);controls.target.lerp(tgt,1-Math.exp(-6*dt));camera.position.copy(controls.target).add(d);}
  controls.update();renderer.render(scene,camera);
}
requestAnimationFrame(tick);
addEventListener('resize',()=>{camera.aspect=innerWidth/innerHeight;camera.updateProjectionMatrix();renderer.setSize(innerWidth,innerHeight);});

/* ===================== DEMO DOCK ===================== */
Object.keys(ST).forEach(k=>{const b=document.createElement('button');b.dataset.s=k;b.innerHTML='<span>'+ST[k].ic+'</span>'+ST[k].ar;
  b.onclick=()=>setState(k);if(k===state)b.classList.add('on');$('dock').appendChild(b);});

/* ===================== BOOT ===================== */
(async()=>{
  try{
    const [g,a]=await Promise.all([loadGLB('jarvis.glb'),loadGLB('anim.glb')]);
    const lib={};a.animations.forEach(c=>{if(['idle','agree','headShake'].includes(c.name))lib[c.name]=bake(a,c);});
    G=g;B=g.userData.bones;mixer=g.userData.mixer;
    for(const n in lib)ACT[n]=mixer.clipAction(makeClip(n,lib[n],g));
    g.scene.traverse(o=>{if(!o.isMesh)return;(Array.isArray(o.material)?o.material:[o.material]).forEach(fadeMat);
      if(o.morphTargetDictionary)faces.push(o);});
    mixer.addEventListener('finished',()=>{if(state==='success'||state==='error')setState('idle');else fadeTo('idle');});
    scene.add(g.userData.wrap);fadeTo('idle');
    if(window.JARVIS_INIT)setState(window.JARVIS_INIT);
    $('load').classList.add('off');
    try{window.JarvisHost&&window.JarvisHost.onReady&&window.JarvisHost.onReady();}catch(_){}
  }catch(e){console.error(e);$('load').querySelector('p').textContent='تعذّر تحميل جارفيس — أعد المحاولة';}
})();
