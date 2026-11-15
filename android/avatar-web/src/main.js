import * as THREE from 'three';
import {OrbitControls} from 'three/addons/controls/OrbitControls.js';
import {RoomEnvironment} from 'three/addons/environments/RoomEnvironment.js';
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';
import {ParametricGeometry} from 'three/addons/geometries/ParametricGeometry.js';

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
const keyL=new THREE.DirectionalLight('#fff1de',2.1); keyL.position.set(1.4,2.2,2.6); scene.add(keyL);
const fillL=new THREE.DirectionalLight('#cfe0ff',.55); fillL.position.set(-2,.6,1.6); scene.add(fillL);
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
function fadeMat(m,x={}){
  m.onBeforeCompile=sh=>{Object.assign(sh.uniforms,FU,x.uniforms||{});
    sh.vertexShader='varying float vWY;\n'+(x.vHead||'')+sh.vertexShader.replace('#include <begin_vertex>','#include <begin_vertex>\n'+(x.vBegin||''))
      .replace('#include <project_vertex>','#include <project_vertex>\n vWY=(modelMatrix*vec4(transformed,1.0)).y;');
    sh.fragmentShader='varying float vWY;uniform float uNeck,uLen,uGray;uniform vec3 uCol;\n'+(x.fHead||'')+sh.fragmentShader
      .replace('#include <color_fragment>','#include <color_fragment>\n'+(x.fColor||''))
      .replace('#include <normal_fragment_maps>','#include <normal_fragment_maps>\n'+(x.fNormal||''))
      .replace('#include <opaque_fragment>',(x.fLight||'')+'\n#include <opaque_fragment>')
      .replace('#include <dithering_fragment>',
      `#include <dithering_fragment>
       float jf=smoothstep(uNeck-uLen,uNeck,vWY);
       float jn=fract(sin(dot(gl_FragCoord.xy,vec2(12.9898,78.233)))*43758.5453);
       if(jf<=jn*.999+.0005) discard;
       gl_FragColor.rgb=mix(gl_FragColor.rgb,vec3(dot(gl_FragColor.rgb,vec3(.3,.59,.11))),uGray);
       gl_FragColor.rgb+=uCol*pow(1.-jf,1.3)*.35;`);};
  if(x.key)m.customProgramCacheKey=()=>x.key;
  m.needsUpdate=true;}

/* ===================== HAIR, BROWS AND STUBBLE =====================
   The sculpt is bald. Everything here is placed in the head's own box (vH: x across, y chin to crown,
   z back to front, each 0..1) so it stays put while the face moves.
   Hair is grown as shells: the scalp drawn again and again, each copy a little further out and keeping
   fewer strands, which reads as short cropped hair with a soft outline. */
const HU={uHM:{value:new THREE.Matrix4()},uBSize:{value:new THREE.Vector3(1,1,1)},uHair:{value:new THREE.Color('#2a1d15')},uDbg:{value:0}};
const HAIR_GLSL=`
uniform vec3 uBSize,uHair; uniform float uDbg;
float h31(vec3 p){p=fract(p*.1031);p+=dot(p,p.yzx+33.33);return fract((p.x+p.y)*p.z);}
float vn3(vec3 p){vec3 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);
  return mix(mix(mix(h31(i),h31(i+vec3(1,0,0)),f.x),mix(h31(i+vec3(0,1,0)),h31(i+vec3(1,1,0)),f.x),f.y),
             mix(mix(h31(i+vec3(0,0,1)),h31(i+vec3(1,0,1)),f.x),mix(h31(i+vec3(0,1,1)),h31(i+vec3(1,1,1)),f.x),f.y),f.z);}
// how far inside the hairline a point is (negative: bare skin)
float hairIn(vec3 n){
  float sx=abs(n.x-.5)*2.;
  float H=mix(.15,.63,smoothstep(.31,.37,n.z));                 // all of the back; curving up round the ear
  H=mix(H,.50,smoothstep(.445,.475,n.z));                         // sideburn in front of the ear
  H=mix(H,.77,smoothstep(.60,.69,n.z));                         // temple
  H=mix(H,.865+sx*sx*.04,smoothstep(.72,.87,n.z));              // forehead, a touch higher at the corners
  return n.y-H+(vn3(n*90.)-.5)*.035;}
`;
const SKIN_X={key:'skin',uniforms:HU,
  vHead:'varying vec3 vH;uniform mat4 uHM;\n',vBegin:'vH=(uHM*vec4(position,1.)).xyz;',
  fHead:'varying vec3 vH;float vSkinLip=0.;'+HAIR_GLSL,
  fNormal:`{float hgt=vn3(vH*uBSize*2600.)*.6+vn3(vH*uBSize*900.)*.4;
    vec3 dpx=dFdx(-vViewPosition),dpy=dFdy(-vViewPosition);float hx=dFdx(hgt),hy=dFdy(hgt);
    vec3 r1=cross(dpy,normal),r2=cross(normal,dpx);float det=dot(dpx,r1);
    normal=normalize(abs(det)*normal-sign(det)*(hx*r1+hy*r2)*.00011);}`,
  fLight:`{float fr=pow(1.-clamp(dot(normalize(normal),normalize(vViewPosition)),0.,1.),2.2);
    outgoingLight+=diffuseColor.rgb*vec3(.55,.16,.10)*fr*.55;          // light bleeding through at grazing angles
    outgoingLight=mix(outgoingLight,outgoingLight*vec3(1.05,.97,.95),.5);}`,
  fColor:`{float sx=abs(vH.x-.5)*2.;
    float hi=hairIn(vH);
    // scalp under the hair, shading off into the skin so the hairline is not a painted edge
    diffuseColor.rgb=mix(diffuseColor.rgb,uHair*.55,smoothstep(-.05,.05,hi)*.94);
    // eyebrows
    vec2 bp=vec2((sx-BR_X)/BR_W,(vH.y-(BR_Y+BR_ARCH*(1.-pow(abs((sx-BR_X)/BR_W),2.))-sx*BR_TILT))/BR_H);
    float br=smoothstep(1.,.35,length(bp))*smoothstep(.55,.8,vH.z)*(.55+.45*vn3(vH*vec3(160.,420.,160.)));
    diffuseColor.rgb=mix(diffuseColor.rgb,uHair*.5,clamp(br*BR_K,0.,.9));
    // a day's stubble on the jaw, chin and upper lip
    float jaw=smoothstep(ST_TOP,ST_TOP-.10,vH.y)*smoothstep(.36,.56,vH.z);
    float lipHole=smoothstep(.0,1.,length(vec2(sx/LP_W,(vH.y-LP_Y)/LP_H)));
    float cheek=smoothstep(ST_TOP-.02,ST_TOP-.16,vH.y+ (1.-sx)*CH_DIP*smoothstep(.75,1.,vH.z));
    float st=jaw*cheek*lipHole*(.6+.4*vn3(vH*520.));
    diffuseColor.rgb=mix(diffuseColor.rgb,diffuseColor.rgb*vec3(.62,.60,.62)+uHair*.10,st*ST_K);
    // lips, and blood nearer the surface on cheeks, nose and ears
    float lip=smoothstep(1.,.55,length(vec2(sx/LIP_W,(vH.y-LIP_Y)/LIP_H)))*smoothstep(.78,.86,vH.z);
    diffuseColor.rgb=mix(diffuseColor.rgb,diffuseColor.rgb*vec3(1.02,.66,.64),lip*.85);
    float flush=smoothstep(1.,.2,length(vec2((sx-.5)/.26,(vH.y-.36)/.11)))*smoothstep(.6,.8,vH.z)
      +smoothstep(1.,.3,length(vec2(sx/.16,(vH.y-.385)/.05)))*smoothstep(.9,.97,vH.z)*.8
      +smoothstep(.86,.97,sx)*.9;
    diffuseColor.rgb*=mix(vec3(1.),vec3(1.06,.90,.88),clamp(flush,0.,1.)*.75);
    // skin is never one flat colour
    diffuseColor.rgb*=.94+.12*vn3(vH*vec3(38.,52.,38.));
    vSkinLip=lip;
    if(uDbg>.5){vec3 g=fract(vH*10.);float l=step(.94,g.y)+step(.94,g.z)*.6+step(.94,g.x)*.3;diffuseColor.rgb=mix(diffuseColor.rgb,vec3(1.,0.,0.),min(l,1.));
      if(fract(vH.y*2.)>.97||fract(vH.z*2.)>.97)diffuseColor.rgb=vec3(0.,1.,0.);}
  }`};
const P={BR_X:'.36',BR_W:'.26',BR_Y:'.600',BR_ARCH:'.012',BR_TILT:'.0',BR_H:'.022',BR_K:'1.1',ST_TOP:'.36',LP_W:'.30',LP_Y:'.215',LP_H:'.035',CH_DIP:'.0',ST_K:'.55',LIP_W:'.30',LIP_Y:'.222',LIP_H:'.036'};
for(const k in P)SKIN_X.fColor=SKIN_X.fColor.split(k).join(P[k]);
const SHELLS=22, HAIR_LEN=.012;          // metres at the crown
function growHair(face,parent){
  const g=face.geometry,pos=g.attributes.position,_p=new THREE.Vector3();
  // keep only the triangles that can carry hair
  const idx=g.index.array,keep=[];const ny=i=>_p.fromBufferAttribute(pos,i).applyMatrix4(HU.uHM.value).y, nz=i=>_p.fromBufferAttribute(pos,i).applyMatrix4(HU.uHM.value).z;
  for(let i=0;i<idx.length;i+=3){const a=idx[i],b=idx[i+1],c=idx[i+2];
    if(Math.max(ny(a),ny(b),ny(c))>.2&&Math.min(nz(a),nz(b),nz(c))<.93)keep.push(a,b,c);}
  const hg=new THREE.BufferGeometry();hg.setAttribute('position',pos);hg.setAttribute('normal',g.attributes.normal);hg.setIndex(keep);
  const ws=face.getWorldScale(new THREE.Vector3()).x, grp=new THREE.Group();
  for(let i=1;i<=SHELLS;i++){const h=i/SHELLS;
    const m=new THREE.MeshStandardMaterial({color:'#ffffff',roughness:.62,metalness:0});
    fadeMat(m,{key:'hair',uniforms:{...HU,uH:{value:h},uLenO:{value:HAIR_LEN/ws}},
      vHead:'varying vec3 vH;varying float vLs;uniform mat4 uHM;uniform float uH,uLenO;\n',
      vBegin:`vH=(uHM*vec4(position,1.)).xyz;
        vLs=mix(.28,1.,smoothstep(.60,.84,vH.y))*mix(1.,1.25,smoothstep(.7,.9,vH.z)*smoothstep(.8,.9,vH.y));
        vec3 nn=normalize(objectNormal);
        // strands lean forward and a little down as they get longer
        transformed+=(nn+vec3(0.,-.35,.55)*uH*uH)*uLenO*uH*vLs;`,
      fHead:'varying vec3 vH;varying float vLs;uniform float uH;'+HAIR_GLSL,
      fColor:`{float hi=hairIn(vH);if(hi<0.)discard;
        vec3 q=vH*uBSize*STRAND;float r=h31(floor(q)),r2=h31(floor(q*.37)+7.);
        float thick=mix(.30,1.,smoothstep(0.,.10,hi));
        if(r*thick<uH*uH*.96+.02)discard;
        diffuseColor.rgb=uHair*(.50+1.25*uH)*(.75+.5*r2);}`.replace('STRAND','2400.')});
    const sh=new THREE.Mesh(hg,m);sh.frustumCulled=false;sh.renderOrder=i;grp.add(sh);}
  grp.position.copy(face.position);grp.quaternion.copy(face.quaternion);grp.scale.copy(face.scale);parent.add(grp);return grp;}

/* ===================== NECK AND CLOTHES =====================
   Only the top of them is ever seen: a neck, the collar of a white tee and a zipped track jacket,
   all thinning into light below the collarbone. They stay still while the head turns. */
const body=new THREE.Group(); scene.add(body);
function canvasTex(w,h,draw,rep){const c=document.createElement('canvas');c.width=w;c.height=h;draw(c.getContext('2d'),w,h);
  const t=new THREE.CanvasTexture(c);t.colorSpace=THREE.SRGBColorSpace;t.anisotropy=4;if(rep){t.wrapS=t.wrapT=THREE.RepeatWrapping;t.repeat.set(rep[0],rep[1]);}return t;}
function weave(x,w,h,base,amt){x.fillStyle=base;x.fillRect(0,0,w,h);const d=x.getImageData(0,0,w,h),p=d.data;
  for(let i=0;i<p.length;i+=4){const X=(i/4)%w,Y=(i/4/w)|0,n=(Math.random()-.5)*amt+((X+Y)%3===0?amt*.35:0)-((X-Y)%4===0?amt*.25:0);p[i]+=n;p[i+1]+=n;p[i+2]+=n;}x.putImageData(d,0,0);}
function ringSurf(f,su,sv,mat){const g=new ParametricGeometry((u,v,t)=>f(u,v,t),su,sv);g.computeVertexNormals();const m=new THREE.Mesh(g,mat);m.frustumCulled=false;body.add(m);return m;}
const sstep=(a,b,x)=>{x=clamp((x-a)/(b-a),0,1);return x*x*(3-2*x);}, mix=(a,b,t)=>a+(b-a)*t;
function buildBody(){
  const NZ=y=>-.03+y*.13;                                    // the neck leans forward as it rises
  // neck, darker where the jaw shades it
  const skinTex=canvasTex(8,128,(x,w,h)=>{const g=x.createLinearGradient(0,0,0,h);g.addColorStop(0,'#33211a');g.addColorStop(.30,'#5e4233');g.addColorStop(.48,'#86614c');g.addColorStop(1,'#946c55');x.fillStyle=g;x.fillRect(0,0,w,h);});
  const skin=new THREE.MeshPhysicalMaterial({map:skinTex,roughness:.56,metalness:0,sheen:.5,sheenColor:new THREE.Color('#ffb59a'),sheenRoughness:.45,specularIntensity:.5});
  fadeMat(skin,{key:'neck',vHead:'varying vec3 vNP;\n',vBegin:'vNP=position;',fHead:'varying vec3 vNP;'+HAIR_GLSL,
    fColor:'diffuseColor.rgb*=.93+.14*vn3(vNP*vec3(160.,220.,160.));'});
  ringSurf((u,v,t)=>{const a=u*Math.PI*2,y=mix(.10,-.20,v),w=sstep(-.05,-.19,y);
    const up=sstep(-.02,.07,y);
    // two cords run from behind the ear to the collarbone, with a hollow between them at the throat
    const cord=Math.exp(-Math.pow((Math.abs(Math.sin(a))-.62)/.2,2))*Math.max(0,Math.cos(a)+.25)*.0035;
    const rx=.059+.06*w+.010*up+.004*Math.cos(a*2)+cord,rz=.062+.012*w+.008*up+cord;
    // the throat stands a little proud in front
    const adam=Math.exp(-Math.pow((y+.02)/.016,2))*Math.max(0,Math.cos(a))**6*.004;
    t.set(Math.sin(a)*rx,y,NZ(y)+Math.cos(a)*(rz+adam));},48,24,skin);
  // white tee: a ribbed crew neck, lower in front
  const teeTex=canvasTex(256,256,(x,w,h)=>{weave(x,w,h,'#e4e4e0',10);x.fillStyle='rgba(0,0,0,.10)';for(let i=0;i<w;i+=4)x.fillRect(i,0,1,30);x.fillStyle='rgba(0,0,0,.25)';x.fillRect(0,30,w,2);},[6,1]);
  const tee=new THREE.MeshStandardMaterial({map:teeTex,roughness:.9,metalness:0,side:THREE.DoubleSide});fadeMat(tee);
  ringSurf((u,v,t)=>{const a=u*Math.PI*2,front=Math.cos(a)*.5+.5;
    const top=mix(-.014,-.056,front), y=mix(top,-.25,v*v), w=sstep(top-.01,-.22,y);
    const rx=.0625+.09*w,rz=.0665+.035*w;
    t.set(Math.sin(a)*rx,y,NZ(y)+Math.cos(a)*rz);},64,20,tee);
  // track jacket: stand collar, zip pulled part of the way down
  const VC=.36;                                                 // where the collar meets the body, in v and in the texture
  const jkTex=canvasTex(1024,512,(x,w,h)=>{
    weave(x,w,h,'#4f9bd6',9);
    const seam=h*VC;
    x.fillStyle='rgba(0,20,60,.10)';for(let i=0;i<w;i+=6)x.fillRect(i,0,2,seam);            // ribbed collar
    x.fillStyle='rgba(5,20,50,.45)';x.fillRect(0,seam-2,w,3);x.fillStyle='rgba(255,255,255,.22)';x.fillRect(0,seam+2,w,1);
    x.fillStyle='#f4f6f8';x.fillRect(0,10,w,7);x.fillRect(0,24,w,4);                        // two white tipping lines
    x.strokeStyle='#12203a';x.lineWidth=4;for(const sx of [.27,.73]){x.beginPath();x.moveTo(w*sx,seam+3);x.lineTo(w*sx,h);x.stroke();}   // shoulder piping
    for(const e of [0,1]){const X=e?w-20:0;x.fillStyle='#eef1f4';x.fillRect(X,0,20,h);       // zip tape, then metal teeth
      x.fillStyle='rgba(0,0,0,.2)';x.fillRect(e?X:X+19,0,1,h);
      const tx=e?w-7:0;for(let y=0;y<h;y+=6){x.fillStyle='#d3d8df';x.fillRect(tx,y,7,4);x.fillStyle='#7d8590';x.fillRect(tx,y+4,7,2);}}
  });
  const jk=new THREE.MeshStandardMaterial({map:jkTex,roughness:.55,metalness:0,side:THREE.DoubleSide});fadeMat(jk);
  ringSurf((u,v,t)=>{
    const open=mix(.36,.03,sstep(.0,.72,v));                   // the V of the open zip, in radians each side
    const a=mix(open,Math.PI*2-open,u), front=Math.cos(a)*.5+.5, edge=Math.pow(Math.abs(u-.5)*2,10);
    const top=mix(.024,-.030,front), base=mix(-.035,-.072,front);
    const cv=clamp(v/VC,0,1), bv=clamp((v-VC)/(1-VC),0,1);
    const y=v<VC?mix(top,base,cv):mix(base,-.27,bv), w=sstep(0,1,bv*.9);
    const flare=(1-cv)*(1-cv)*(.004+.014*edge);                // the collar leans away from the neck, more at its points
    const rx=.069+.18*w+flare,rz=.074+.074*w+flare;
    t.set(Math.sin(a)*rx,y,NZ(Math.max(y,-.06))+Math.cos(a)*rz);},96,32,jk);
}
buildBody();

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

const HEAD_H=.23, HEAD_Z=.022;          // metres: crown to chin, and how far the head sits ahead of the neck
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
function camDist(){const a=innerWidth/innerHeight;return Math.max(1.14,.86/a)*(EMBED?.84:1);}
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
  both('eyeSquint',.28);both('eyeBlink',.10);both('browDown',.12);   // a resting face is never wide-eyed
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
    pPos[i*3]=Math.cos(a)*r*2.6;pPos[i*3+1]=-.12-ph*.14;pPos[i*3+2]=Math.sin(a)*r*1.3-.01;}
  pGeo.attributes.position.needsUpdate=true;motes.material.opacity=.7*(1-S.gray*.6);
  // camera keeps the head framed (a little closer while speaking / listening)
  S.camZ=damp(S.camZ,state==='speaking'||state==='listening'?.94:1,3,dt);
  const tgt=_v.set(0,HEAD_H*.33,0);
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
        o.material=new THREE.MeshPhysicalMaterial({map:o.material.map,color:'#ecbb9f',roughness:.52,metalness:0,
          sheen:.5,sheenColor:new THREE.Color('#ffb59a'),sheenRoughness:.45,specularIntensity:.55});
        fadeMat(o.material,SKIN_X);}
      else{let e=o,eye=false;while(e){if(e===eyeL||e===eyeR||/eye/i.test(e.name))eye=true;e=e.parent;}
        if(eye){ // a wet cornea over a darker iris
          o.material=new THREE.MeshPhysicalMaterial({map:o.material.map,roughness:.28,metalness:0,clearcoat:1,clearcoatRoughness:.04});
          fadeMat(o.material,{key:'eye',fColor:`{float l=dot(diffuseColor.rgb,vec3(.3,.59,.11));
            float iris=1.-smoothstep(.50,.74,l);
            diffuseColor.rgb=mix(diffuseColor.rgb*vec3(.97,.94,.92),diffuseColor.rgb*vec3(.52,.36,.24)*1.05,iris);}`});}
        else{o.material=o.material.clone();o.material.roughness=.3;fadeMat(o.material);}}
    });
    if(!face)throw new Error('no face mesh');
    dict=face.morphTargetDictionary;inf=face.morphTargetInfluences;
    if(eyeL){eyeRest.l.copy(eyeL.rotation);eyeRest.r.copy(eyeR.rotation);}
    // Stand the head on the origin: chin at 0, crown HEAD_H up, turning around the top of the neck.
    // The file's own bounds include every blendshape's reach, so the box is measured from the resting face.
    pivot.add(model);pivot.updateMatrixWorld(true);
    const box=new THREE.Box3(),rp=face.geometry.attributes.position,_p=new THREE.Vector3();
    for(let i=0;i<rp.count;i++)box.expandByPoint(_p.fromBufferAttribute(rp,i).applyMatrix4(face.matrixWorld));
    const size=box.getSize(new THREE.Vector3()),c=box.getCenter(new THREE.Vector3()),k=HEAD_H/size.y;
    model.scale.multiplyScalar(k);model.position.set(-c.x*k,-box.min.y*k,-c.z*k+HEAD_Z);
    pivot.updateMatrixWorld(true);
    size.multiplyScalar(k);HU.uBSize.value.copy(size);
    HU.uHM.value.makeScale(1/size.x,1/size.y,1/size.z).multiply(new THREE.Matrix4().makeTranslation(size.x/2,0,size.z/2-HEAD_Z)).multiply(face.matrixWorld);
    growHair(face,face.parent);
    FU.uNeck.value=-.085;FU.uLen.value=.09;       // the jaw is whole; below it he thins into light
    ready=true;
    if(window.JARVIS_INIT)setState(window.JARVIS_INIT);
    $('load').classList.add('off');
    try{window.JarvisHost&&window.JarvisHost.onReady&&window.JarvisHost.onReady();}catch(_){}
  }catch(e){console.error(e);$('load').querySelector('p').textContent='تعذّر تحميل جارفيس — أعد المحاولة';}
})();
