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
const FU={uNeck:{value:0},uLen:{value:.05},uCol:{value:acc},uGray:{value:0}};
function fadeMat(m){
  m.onBeforeCompile=sh=>{Object.assign(sh.uniforms,FU);
    sh.vertexShader='varying float vWY;\n'+sh.vertexShader.replace('#include <project_vertex>','#include <project_vertex>\n vWY=(modelMatrix*vec4(transformed,1.0)).y;');
    sh.fragmentShader='varying float vWY;uniform float uNeck,uLen,uGray;uniform vec3 uCol;\n'+sh.fragmentShader.replace('#include <dithering_fragment>',
      `#include <dithering_fragment>
       float jf=smoothstep(uNeck-uLen,uNeck,vWY);
       float jn=fract(sin(dot(gl_FragCoord.xy,vec2(12.9898,78.233)))*43758.5453);
       if(jf<=jn*.999+.0005) discard;
       gl_FragColor.rgb=mix(gl_FragColor.rgb,vec3(dot(gl_FragColor.rgb,vec3(.3,.59,.11))),uGray);
       gl_FragColor.rgb+=uCol*pow(1.-jf,1.3)*.35;`);};
  m.needsUpdate=true;}

/* ===================== MODEL: A SCULPTED HEAD WITH 52 FACIAL BLENDSHAPES =====================
   No skeleton. The head turns as one piece around the top of the neck, the eyeballs are their own
   nodes, and everything the face does — jaw, lips, lids, brows, cheeks — is a blendshape. */
try{self.createImageBitmap=undefined;}catch(e){}
const loader=new GLTFLoader();
function b64buf(b){const s=atob(b),n=s.length,a=new Uint8Array(n);for(let i=0;i<n;i++)a[i]=s.charCodeAt(i);return a.buffer;}
const loadModel=()=>new Promise((res,rej)=>{
  // the app ships the .glb itself; the hosted preview can only serve it wrapped in a script
  const wrapped=window.JARVIS_MODULE;
  if(wrapped)import(wrapped).then(m=>loader.parse(b64buf(m.default),'',res,rej),rej);
  else loader.load('face.glb',res,undefined,rej);});

const HEAD_H=.25;                       // metres, crown to chin
const pivot=new THREE.Group(); scene.add(pivot);   // turns the whole head
let face=null, dict=null, inf=null, eyeL=null, eyeR=null, ready=false;
const eyeRest={l:new THREE.Euler(),r:new THREE.Euler()};
const W={};                             // blendshape weights for this frame
const put=(n,v)=>{if(v>(W[n]||0))W[n]=v;};
const both=(n,v)=>{put(n+'_L',v);put(n+'_R',v);};

/* ===================== PUBLIC API ===================== */
let smileUntil=0, T=0;
function setState(s){
  s=String(s||'').toLowerCase(); if(!ST[s]||s===state&&s!=='success'&&s!=='error')return;
  state=s; accT.set(ST[s].c);
  document.documentElement.style.setProperty('--acc',ST[s].c); $('stt').textContent=ST[s].ar;
  [...$('dock').children].forEach(b=>b.classList.toggle('on',b.dataset.s===s));
  if(s==='success'){gestures.push({type:'yes',at:T,dur:.9});smileUntil=T+2.2;}
  if(s==='error')gestures.push({type:'no',at:T,dur:1.1});
  if(!EMBED){const say=$('say');
    const txt={speaking:'مرحبًا، أنا جارفيس. كيف أستطيع مساعدتك؟',listening:'أنا أسمعك…',thinking:'لحظة، أفكّر في ذلك…',executing:'جاري تنفيذ الأمر…',
      success:'تمّ بنجاح.',error:'عذرًا، حدث خطأ.',disconnected:'انقطع الاتصال.',understanding:'أحلّل ما قلته…'}[s];
    if(txt){say.textContent=txt;say.classList.remove('off');}else say.classList.add('off');
    if(s==='speaking')speakText(txt);
    if(s==='success'||s==='error')setTimeout(()=>{if(state===s)setState('idle');},2400);}
}
function setLevel(v){level=clamp(+v||0,0,1);levelAt=performance.now();}

/* ===================== SPEECH: LIPS SHAPED BY THE TEXT, FACE BY ITS MEANING =====================
   Each letter becomes a mouth target — how open, how wide or rounded, lips pressed or not — and the
   real loudness of the playing audio gates it. Timing is estimated from the text, not phoneme-aligned. */
let talk=null;                 // {open,wide,press,cues,dur,t0,armed}
const gestures=[];             // {type,at,dur}
let nextFidget=5;
const HARAKAT={'َ':[.95,0],'ُ':[.5,-.8],'ِ':[.4,.8],'ْ':[.05,0]};
function shapeOf(ch){          // [open, wide(+)/round(-), pressed lips]
  if(/[اآأإىaà-å]/i.test(ch))return[1,.1,0];
  if(/[وؤoò-öuù-üw]/i.test(ch))return[.5,-.9,0];
  if(/[يئeè-ëiì-ïy]/i.test(ch))return[.4,.85,0];
  if(/[مبپpbm]/i.test(ch))return[0,0,1];
  if(/[فvf]/i.test(ch))return[.1,.2,.45];
  if(/[عحهخغقhg]/i.test(ch))return[.62,0,0];
  if(/[سشزصثذظszcjچج]/i.test(ch))return[.16,.55,0];
  if(/[تدطنلرضtdnlr]/i.test(ch))return[.3,.25,0];
  if(/[0-9٠-٩]/.test(ch))return[.6,.2,0];
  return[.3,.15,0];
}
function buildTalk(text){
  const open=[],wide=[],press=[];
  const push=(o,w,p,n)=>{for(let i=0;i<n;i++){open.push(o);wide.push(w);press.push(p);}};
  for(const ch of text){
    const h=HARAKAT[ch];
    if(h){if(open.length){open[open.length-1]=h[0];wide[wide.length-1]=h[1];}continue;}
    if(/\s/.test(ch)){push(.05,0,0,1);continue;}
    if(/[.!?؟…\n]/.test(ch)){push(0,0,.25,5);continue;}
    if(/[,،;؛:—-]/.test(ch)){push(0,0,.15,3);continue;}
    if(!/[\p{L}\p{N}]/u.test(ch))continue;
    const [o,w,p]=shapeOf(ch);
    push(o*.5,w,p,1);push(o,w,p*.5,1);    // the consonant closes a little, then its vowel opens
  }
  return{open,wide,press,cues:[],dur:Math.max(.5,open.length*.036),t0:-1,armed:performance.now()};
}
const CUES=[
  ['laugh',/(ههه+|haha+|\blol\b)/i],
  ['no',/(آسف|عذر|للأسف|لا أستطيع|لا يمكن|لم أتمكن|تعذّر|تعذر|فشل|خطأ|sorry|cannot|can't|unable|failed|unfortunately)/i],
  ['warn',/(انتبه|تحذير|احذر|خطر|warning|careful|caution|danger)/i],
  ['greet',/(مرحب|أهلا|أهلًا|السلام عليكم|صباح|مساء|hello|\bhi\b|\bhey\b|welcome)/i],
  ['joy',/(رائع|ممتاز|جميل|عظيم|مبروك|أحسنت|great|awesome|excellent|perfect|congrat|well done)/i],
  ['think',/(ربما|أظن|أعتقد|لحظة|دعني|قد يكون|maybe|perhaps|i think|let me|hmm)/i],
  ['yes',/(^|[\s"«])(نعم|أجل|تمّ|تم|حاضر|بالتأكيد|طبعًا|طبعا|تمام|أكيد|yes|sure|done|okay|ok|certainly|of course)(?=$|[\s.,!،؛"»])/i],
  ['ask',/[?؟]/]];
const GDUR={yes:.9,no:1.1,ask:1.4,greet:1.2,joy:1.4,think:1.7,laugh:1.3,warn:1.2,beat:.5,glance:1.6};
function cueFor(clause){for(const [t,re] of CUES)if(re.test(clause))return t;return /!/.test(clause)?'joy':'beat';}
/** Tell the head what is being said: the lips follow the letters, the face reacts to the meaning. */
function speakText(text){
  text=String(text||'').trim();if(!text)return;
  talk=buildTalk(text);
  const parts=text.split(/(?<=[.!?؟…\n,،;؛:])/);let pos=0;const total=Math.max(1,text.length);
  talk.cues=parts.filter(p=>p.trim()).map(p=>{const c={type:cueFor(p),at:pos/total};pos+=p.length;return c;});
}
window.Jarvis={setState,setLevel,say:speakText,states:Object.keys(ST),get state(){return state;}};
addEventListener('message',e=>{const d=e.data;if(!d||d.type!=='jarvis')return;if(d.state)setState(d.state);if(d.level!=null)setLevel(d.level);if(d.say)speakText(d.say);});

/* ===================== INPUT ===================== */
let px=0,py=0,pAt=-1e9,down=null;
const cv=renderer.domElement;
addEventListener('pointermove',e=>{px=(e.clientX/innerWidth)*2-1;py=(e.clientY/innerHeight)*2-1;pAt=performance.now();});
cv.addEventListener('pointerdown',e=>{down={x:e.clientX,y:e.clientY,t:performance.now()};px=(e.clientX/innerWidth)*2-1;py=(e.clientY/innerHeight)*2-1;pAt=down.t;});
cv.addEventListener('pointerup',e=>{if(down&&performance.now()-down.t<320&&Math.hypot(e.clientX-down.x,e.clientY-down.y)<10){ // tap = greet
  smileUntil=T+1.8;gestures.push({type:'greet',at:T,dur:1.1});try{window.JarvisHost&&window.JarvisHost.onTap&&window.JarvisHost.onTap();}catch(_){} }down=null;});

/* ===================== LOOP ===================== */
const S={pitch:0,yaw:0,roll:0,ex:0,ey:0,open:0,wide:0,press:0,smile:0,lv:0,gray:0,hudS:1,camZ:1,brow:0,frown:0,wideEye:0,squint:0};
let last=performance.now(),first=true,blinkAt=2,blinkT=-1,blinkTwice=false;
const cen=new THREE.Vector3(),_v=new THREE.Vector3();
function camDist(){const a=innerWidth/innerHeight;return Math.max(.74,.6/a)*(EMBED?.84:1);}
function tick(now){
  requestAnimationFrame(tick);
  const dt=Math.min((now-last)/1000,.05);last=now;T+=dt;
  if(!ready){renderer.render(scene,camera);return;}
  // audio level: the real one from the host when fresh, otherwise a synthetic envelope so the demo is alive
  const ext=now-levelAt<350;
  let lv=ext?level:0;
  if(!ext&&state==='speaking')lv=clamp(.35+.45*Math.sin(T*9.3)*Math.sin(T*3.1+1)+.25*Math.sin(T*17.7),0,1);
  if(!ext&&state==='listening')lv=.25+.2*Math.sin(T*5.2)*Math.sin(T*1.7);
  S.lv=damp(S.lv,lv,18,dt);

  // ---- where he looks and how the head sits, by state ----
  const fresh=now-pAt<3500; let ty=0,tp=0,tr=0,tex=0,tey=0,brow=0,frown=0,wideEye=0,squint=0,smile=0;
  if(fresh){ty=px*.5;tp=py*.3;tex=px*.5;tey=py*.35;}else{ty=Math.sin(T*.31)*.14+Math.sin(T*.13)*.08;tp=Math.sin(T*.23)*.05;tex=Math.sin(T*.7)*.25;tey=Math.sin(T*.43)*.1;}
  if(state==='idle')smile=.16;
  if(state==='listening'){tr+=.1;tp+=.06;brow=.35;wideEye=.25;smile=.12;}
  if(state==='understanding'){tr-=.06;tp+=.03;brow=.2;squint=.25;}
  if(state==='thinking'){ty=.24+Math.sin(T*.8)*.07;tp=-.15;tex=.6;tey=-.55;tr=-.05;frown=.35;squint=.2;}
  if(state==='executing'){tex=Math.sin(T*6.5)*.55;tp+=.05;squint=.3;}
  if(state==='speaking'){tp+=S.lv*.04*Math.sin(T*7);ty+=Math.sin(T*1.3)*.05;brow=.1+S.lv*.2;}
  if(state==='success')smile=1;
  if(state==='error'){frown=.6;brow=.3;}
  if(state==='disconnected'){ty=0;tp=.24;tex=0;tey=.5;tr=.03;frown=.3;}
  if(T<smileUntil)smile=1;

  // ---- the sentence being spoken: shapes for the lips, cues for the face ----
  let shO=-1,shW=0,shP=0;
  if(talk){
    if(talk.t0<0&&(S.lv>.035||(!ext&&state==='speaking'&&now-talk.armed>300)))talk.t0=T;
    if(talk.t0<0){if(now-talk.armed>8000)talk=null;}
    else{const u=(T-talk.t0)/talk.dur;
      if(u>=1.15)talk=null;
      else{const n=talk.open.length,f=clamp(u,0,.9999)*n,i=Math.floor(f),k=f-i,j=Math.min(i+1,n-1),mix=a=>a[i]+(a[j]-a[i])*k;
        shO=mix(talk.open);shW=mix(talk.wide);shP=mix(talk.press);
        while(talk.cues.length&&talk.cues[0].at<=u){const c=talk.cues.shift();gestures.push({type:c.type,at:T,dur:GDUR[c.type]||.8});}}}
  }
  // a still face looks switched off: idle, he glances, tilts and raises a brow on his own
  if(state==='idle'&&!talk&&T>nextFidget){gestures.push({type:['ask','think','beat','glance','glance'][Math.floor(Math.random()*5)],at:T,dur:1.6});nextFidget=T+5+Math.random()*7;}
  let gp=0,gy=0,gr=0,gEx=0,gEy=0;
  for(let i=gestures.length-1;i>=0;i--){const g=gestures[i],u=(T-g.at)/g.dur;
    if(u>=1){gestures.splice(i,1);continue;} if(u<0)continue;
    const e=Math.sin(Math.PI*u);
    switch(g.type){
      case'yes':gp+=.16*Math.sin(u*4*Math.PI)*e;smile=Math.max(smile,.5*e);break;
      case'no':gy+=.2*Math.sin(u*5*Math.PI)*e;frown=Math.max(frown,.55*e);brow=Math.max(brow,.4*e);break;
      case'ask':gr+=.13*e;gp-=.06*e;brow=Math.max(brow,.9*e);wideEye=Math.max(wideEye,.3*e);break;
      case'greet':gp+=.13*Math.sin(u*2*Math.PI)*e;smile=Math.max(smile,e);brow=Math.max(brow,.5*e);break;
      case'joy':gp-=.07*e;smile=Math.max(smile,e);brow=Math.max(brow,.6*e);wideEye=Math.max(wideEye,.25*e);break;
      case'think':gy+=.2*e;gp-=.1*e;gEx+=.5*e;gEy-=.5*e;squint=Math.max(squint,.35*e);frown=Math.max(frown,.25*e);break;
      case'laugh':gp+=.05*Math.sin(u*10*Math.PI);smile=1;squint=Math.max(squint,.6*e);break;
      case'warn':gp+=.11*e;gr-=.05*e;frown=Math.max(frown,.7*e);wideEye=Math.max(wideEye,.4*e);break;
      case'glance':gEx+=.7*e*(g.at%2<1?1:-1);break;
      default:gp+=.05*e;brow=Math.max(brow,.25*e);}}
  S.yaw=damp(S.yaw,ty,5,dt);S.pitch=damp(S.pitch,tp,5,dt);S.roll=damp(S.roll,tr,4,dt);
  S.ex=damp(S.ex,clamp(tex+gEx,-1,1),10,dt);S.ey=damp(S.ey,clamp(tey+gEy,-1,1),10,dt);
  S.brow=damp(S.brow,brow,8,dt);S.frown=damp(S.frown,frown,7,dt);S.wideEye=damp(S.wideEye,wideEye,8,dt);
  S.squint=damp(S.squint,squint,8,dt);S.smile=damp(S.smile,smile,6,dt);
  S.gray=damp(S.gray,state==='disconnected'?1:0,4,dt);

  // ---- lips ----
  let mo=0,mw=0,mp=0;
  if(state==='speaking'){
    if(shO>=0){const force=.3+.7*clamp(S.lv*1.8,0,1);mo=shO*force;mw=shW;mp=shP;}   // letters give the shape, the audio the force
    else mo=clamp(S.lv*1.25,0,1);
    if(ext&&S.lv<.02){mo=0;mw=0;mp=0;}
  }
  S.open=damp(S.open,mo,30,dt);S.wide=damp(S.wide,mw,20,dt);S.press=damp(S.press,mp,28,dt);

  // ---- blinking: the single cheapest sign of life ----
  if(blinkT<0&&T>blinkAt){blinkT=0;blinkTwice=Math.random()<.18;}
  let blink=0;
  if(blinkT>=0){blinkT+=dt;const d=.15,u=blinkT/d;blink=u<1?Math.sin(Math.PI*u):0;
    if(u>=1){blinkT=-1;if(blinkTwice){blinkTwice=false;blinkAt=T+.12;}else blinkAt=T+2.2+Math.random()*4.2;}}
  if(state==='disconnected')blink=Math.max(blink,.55);

  // ---- compose the face ----
  for(const k in W)W[k]=0;
  put('jawOpen',S.open*.62);both('mouthLowerDown',S.open*.35);both('mouthUpperUp',S.open*.16);
  const round=Math.max(0,-S.wide),spread=Math.max(0,S.wide);
  put('mouthFunnel',round*.75*(.35+S.open));put('mouthPucker',round*.45);
  both('mouthStretch',spread*.4);both('mouthDimple',spread*.2);
  both('mouthPress',S.press*.75);put('mouthClose',S.press*S.open*.5);put('mouthRollLower',S.press*.3);
  both('mouthSmile',clamp(S.smile*.72+spread*.22,0,1));both('cheekSquint',S.smile*.45);
  both('mouthFrown',S.frown*.7);put('mouthShrugLower',S.frown*.3);
  put('browInnerUp',clamp(S.brow+S.frown*.35,0,1));both('browOuterUp',S.brow*.7);both('browDown',clamp(S.squint*.6+S.frown*.25-S.brow,0,1));
  both('eyeWide',S.wideEye);both('eyeSquint',clamp(S.squint+S.smile*.35,0,1));
  both('eyeBlink',clamp(blink+Math.max(0,S.ey)*.25,0,1));
  // lids follow the gaze, as real ones do
  const lx=S.ex,ly=S.ey;
  put('eyeLookUp_L',Math.max(0,-ly));put('eyeLookUp_R',Math.max(0,-ly));put('eyeLookDown_L',Math.max(0,ly));put('eyeLookDown_R',Math.max(0,ly));
  put('eyeLookOut_L',Math.max(0,lx));put('eyeLookIn_R',Math.max(0,lx));put('eyeLookIn_L',Math.max(0,-lx));put('eyeLookOut_R',Math.max(0,-lx));
  for(const k in W){const i=dict[k];if(i!=null)inf[i]=W[k];}
  if(eyeL){eyeL.rotation.set(eyeRest.l.x+ly*.32,eyeRest.l.y+lx*.42,eyeRest.l.z);eyeR.rotation.set(eyeRest.r.x+ly*.32,eyeRest.r.y+lx*.42,eyeRest.r.z);}

  // ---- the head itself ----
  const breathe=Math.sin(T*1.25);
  pivot.rotation.set(S.pitch*.9+gp+breathe*.008,S.yaw*.95+gy,S.roll+gr,'YXZ');
  pivot.position.y=breathe*.0025;
  pivot.updateMatrixWorld(true);
  cen.set(0,HEAD_H*.52,0).applyMatrix4(pivot.matrixWorld);
  FU.uGray.value=S.gray;

  // colours follow the state
  acc.lerp(accT,1-Math.exp(-5*dt));rimL.color.copy(acc);rimR.color.copy(acc);
  const pulse=state==='thinking'||state==='understanding'?.5+.5*Math.sin(T*3):state==='executing'?.5+.5*Math.sin(T*8):.6;
  rimL.intensity=(.28+S.lv*.4+pulse*.16)*(1-S.gray*.7);rimR.intensity=rimL.intensity*.8;
  rimL.position.set(-.34,HEAD_H*.75,-.3);rimR.position.set(.34,HEAD_H*.4,-.3);
  // HUD rings behind the head
  hud.position.set(0,HEAD_H*.55,-.2);hud.quaternion.copy(camera.quaternion);
  const spin=state==='thinking'||state==='executing'?2.6:state==='disconnected'?.15:1;
  arcs.forEach((m,i)=>{m.rotation.z+=dt*spin*(i%2?-.45:.3)*(1+i*.18);});
  S.hudS=damp(S.hudS,1+S.lv*.16,14,dt);hud.scale.setScalar(S.hudS*.72);
  hudMat.opacity=(.3+S.lv*.5+pulse*.12)*(1-S.gray*.6);glow.material.opacity=(.3+S.lv*.4)*(1-S.gray*.7);
  // motes drifting off where the neck dissolves
  for(let i=0;i<PN;i++){const s=pSeed[i],ph=(T*(.12+s*.16)+s*7.3)%1,a=s*61.7+T*.2*(s-.5),r=.015+((s*13.37)%1)*.075;
    pPos[i*3]=Math.cos(a)*r;pPos[i*3+1]=.035-ph*.2;pPos[i*3+2]=Math.sin(a)*r*.8-.01;}
  pGeo.attributes.position.needsUpdate=true;motes.material.opacity=.7*(1-S.gray*.6);
  // camera keeps the head framed (a little closer while speaking / listening)
  S.camZ=damp(S.camZ,state==='speaking'||state==='listening'?.94:1,3,dt);
  const tgt=_v.set(0,HEAD_H*.5,0);
  if(first){controls.target.copy(tgt);camera.position.set(0,tgt.y+.01,camDist());first=false;}
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
    const g=await loadModel(),model=g.scene;
    model.traverse(o=>{
      if(o.name==='grp_eyeLeft')eyeL=o; if(o.name==='grp_eyeRight')eyeR=o;
      if(!o.isMesh)return; o.frustumCulled=false;
      if(o.morphTargetDictionary&&o.morphTargetDictionary.jawOpen!=null){face=o;
        // the sculpt's texture is near-monochrome: tinting it gives the skin its colour, eyes and teeth keep theirs
        o.material=o.material.clone();o.material.color.set('#dcab8e');o.material.roughness=.58;o.material.metalness=0;}
      else{o.material=o.material.clone();o.material.roughness=.25;}
      fadeMat(o.material);
    });
    if(!face)throw new Error('no face mesh');
    dict=face.morphTargetDictionary;inf=face.morphTargetInfluences;
    if(eyeL){eyeRest.l.copy(eyeL.rotation);eyeRest.r.copy(eyeR.rotation);}
    // stand the head on the origin, crown to chin HEAD_H tall, turning around the top of the neck
    const box=new THREE.Box3().setFromObject(model),size=box.getSize(new THREE.Vector3()),c=box.getCenter(new THREE.Vector3());
    const k=HEAD_H/size.y;model.scale.multiplyScalar(k);
    model.position.set(-c.x*k,-box.min.y*k,-c.z*k+HEAD_H*.06);
    pivot.add(model);
    FU.uNeck.value=HEAD_H*.075;FU.uLen.value=HEAD_H*.075;       // the jaw is whole; below it he thins into light
    ready=true;
    if(window.JARVIS_INIT)setState(window.JARVIS_INIT);
    $('load').classList.add('off');
    try{window.JarvisHost&&window.JarvisHost.onReady&&window.JarvisHost.onReady();}catch(_){}
  }catch(e){console.error(e);$('load').querySelector('p').textContent='تعذّر تحميل جارفيس — أعد المحاولة';}
})();
