(() => {
"use strict";
const $=id=>document.getElementById(id);
const ui={
 boot:$("bootCard"),login:$("loginCard"),loginForm:$("loginForm"),email:$("email"),password:$("password"),loginMsg:$("loginMessage"),
 athlete:$("athleteCard"),athleteName:$("athleteName"),athleteEmail:$("athleteEmail"),logout:$("logoutBtn"),workoutCount:$("workoutCount"),nextDate:$("nextWorkoutDate"),
 mibro:$("mibroCard"),nativeStatus:$("nativeStatus"),notificationStatus:$("notificationStatus"),mibroFitStatus:$("mibroFitStatus"),mibroBridgeAccess:$("mibroBridgeAccess"),bridgeBanner:$("bridgeBanner"),appVersion:$("appVersion"),updateStatus:$("updateStatus"),checkUpdate:$("checkUpdateBtn"),fixMibro:$("fixMibroBtn"),openMibroFit:$("openMibroFitBtn"),testMibro:$("testMibroBtn"),mibroMsg:$("mibroMessage"),
 workouts:$("workoutsCard"),select:$("workoutSelect"),preview:$("workoutPreview"),prepare:$("prepareBtn"),prepareMsg:$("prepareMessage"),
 plan:$("planCard"),planTitle:$("planTitle"),planBlocks:$("planBlocks"),start:$("startBtn"),
 live:$("liveCard"),phase:$("phase"),gpsBadge:$("gpsBadge"),guidance:$("guidance"),timerLabel:$("timerLabel"),timer:$("timer"),target:$("target"),pace:$("pace"),distance:$("distance"),accuracy:$("accuracy"),blockIndex:$("blockIndex"),
 pause:$("pauseBtn"),next:$("nextBtn"),stop:$("stopBtn")
};
const S={auth:null,db:null,user:null,athlete:null,workouts:[],selected:null,plan:null,running:false,paused:false,block:0,blockStarted:0,pauseStarted:0,watchId:null,lastPos:null,distanceM:0,blockStartDistanceM:0,samples:[],paceSec:NaN,accuracyM:NaN,guide:"unknown",guideSince:0,lastGuideAt:0,timer:null};
const GPS_MAX=35, WINDOW_MS=20000, HOLD_MS=12000, OUT_COOLDOWN=22000, GOOD_COOLDOWN=60000, HYST=3;

function show(el,on=true){if(el)el.classList.toggle("hidden",!on)}
function message(el,text,tone){if(!el)return;el.textContent=text||"";el.className=("message "+(tone||"")).trim()}
function norm(v){return String(v||"").replace(/\u00a0/g," ").replace(/[–—]/g,"-").replace(/\s+/g," ").trim()}
function dateValue(w){return String((w&& (w.date||w.data||w.createdAt))||"").slice(0,10)}
function titleOf(w){return (w&&(w.title||w.titulo||w.nome))||"Treino"}
function descOf(w){return (w&&(w.description||w.descricao||w.observacoes))||""}
function done(w){return ["realizado","concluido","concluído"].includes(norm(w&&w.status).toLowerCase())||!!(w&&w.stravaData)}
function displayDate(iso){const p=String(iso||"").slice(0,10).split("-");return p.length===3?p[2]+"/"+p[1]+"/"+p[0]:(iso||"—")}
function fmtTime(sec){sec=Math.max(0,Math.ceil(Number(sec)||0));return String(Math.floor(sec/60)).padStart(2,"0")+":"+String(sec%60).padStart(2,"0")}
function fmtPace(sec){sec=Math.round(Number(sec));return Number.isFinite(sec)&&sec>0?Math.floor(sec/60)+":"+String(sec%60).padStart(2,"0")+"/km":"—"}
function esc(v){return String(v??"").replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;").replaceAll('"',"&quot;").replaceAll("'","&#039;")}
function native(){return !!(window.AtletIANative&&typeof window.AtletIANative.configure==="function")}
function ncall(name,arg){if(!native())return false;try{const fn=window.AtletIANative[name];if(typeof fn==="function"){arg===undefined?fn.call(window.AtletIANative):fn.call(window.AtletIANative,arg);return true}}catch(e){console.warn("native",name,e)}return false}
function speak(text){if(native()||!text||!("speechSynthesis" in window))return;speechSynthesis.cancel();const u=new SpeechSynthesisUtterance(text);u.lang="pt-BR";speechSynthesis.speak(u)}
function vibrate(pattern){if(!native()&&navigator.vibrate)navigator.vibrate(pattern)}
function setGuide(text,tone){ui.guidance.textContent=text;ui.guidance.className=("guidance "+(tone||"")).trim()}

function firebaseReady(){
 if(!window.firebaseConfig||!window.firebase)throw new Error("Firebase não carregou.");
 if(!firebase.apps.length)firebase.initializeApp(window.firebaseConfig);
 S.auth=firebase.auth();S.db=firebase.database();
}
async function readAthlete(uid){return (await S.db.ref("users/"+uid).once("value")).val()||null}
async function loadWorkouts(uid){
 const snaps=await Promise.all([S.db.ref("users/"+uid+"/workouts").once("value"),S.db.ref("data/"+uid+"/workouts").once("value")]);
 const rows=[];
 Object.entries(snaps[0].val()||{}).forEach(([id,data])=>rows.push(Object.assign({id,source:"users"},data)));
 Object.entries(snaps[1].val()||{}).forEach(([id,data])=>rows.push(Object.assign({id,source:"data"},data)));
 const map=new Map();
 rows.forEach(w=>{const k=dateValue(w)+"|"+norm(titleOf(w)).toLowerCase()+"|"+norm(descOf(w)).slice(0,100).toLowerCase();const old=map.get(k);if(!old||(!old.stravaData&&w.stravaData))map.set(k,w)});
 return Array.from(map.values()).sort((a,b)=>new Date(dateValue(b)||0)-new Date(dateValue(a)||0));
}
function chooseDefault(){
 const today=new Date();today.setHours(0,0,0,0);
 return [...S.workouts].sort((a,b)=>{
  const da=new Date(dateValue(a)||0).getTime(),db=new Date(dateValue(b)||0).getTime();
  const af=da>=today.getTime()&&!done(a),bf=db>=today.getTime()&&!done(b);
  if(af!==bf)return af?-1:1;return af?da-db:db-da;
 })[0]||null;
}
function getBridgeStatus(){
 if(!native())return {appVersion:"web",mibroFitInstalled:false,notificationPermission:("Notification" in window)&&Notification.permission==="granted",bridgeAccess:false,web:true};
 try{return JSON.parse(window.AtletIANative.mibroBridgeStatus())}catch(e){return {appVersion:"?",mibroFitInstalled:false,notificationPermission:false,bridgeAccess:false,error:true}}
}
function renderNative(){
 const s=getBridgeStatus();
 if(native()){
  ui.nativeStatus.textContent="APK nativo";
  ui.notificationStatus.textContent=s.notificationPermission?"Permitidas":"BLOQUEADAS";
  ui.mibroFitStatus.textContent=s.mibroFitInstalled?"Instalado":"NÃO INSTALADO";
  ui.mibroBridgeAccess.textContent=s.bridgeAccess?"Ativo":"FALTA ACESSO";
  ui.appVersion.textContent="v"+(s.appVersion||"?");
  if(!s.mibroFitInstalled){ui.bridgeBanner.textContent="Mibro Fit não foi localizado neste celular.";ui.bridgeBanner.className="bridge-banner bad"}
  else if(!s.notificationPermission){ui.bridgeBanner.textContent="As notificações do atletIA Mibro estão bloqueadas no Android.";ui.bridgeBanner.className="bridge-banner bad"}
  else if(!s.bridgeAccess){ui.bridgeBanner.textContent="Mibro Fit ainda não possui acesso para ler e encaminhar as notificações. Toque em CORRIGIR PONTE MIBRO.";ui.bridgeBanner.className="bridge-banner warn"}
  else{ui.bridgeBanner.textContent="Ponte Android pronta. Falta apenas habilitar atletIA Mibro dentro de Notificações do Mibro Fit e confirmar o teste no relógio.";ui.bridgeBanner.className="bridge-banner good"}
 }else{
  ui.nativeStatus.textContent="Web";
  ui.notificationStatus.textContent=("Notification" in window)?Notification.permission:"Indisponível";
  ui.mibroFitStatus.textContent="Somente APK";
  ui.mibroBridgeAccess.textContent="Somente APK";
  ui.appVersion.textContent="WEB";
  ui.bridgeBanner.textContent="Abra pelo APK dedicado para usar a ponte com o GS Pro.";
  ui.bridgeBanner.className="bridge-banner warn";
 }
 return s;
}
window.refreshMibroBridgeStatus=()=>renderNative();
window.AtletIAUpdateStatus=status=>{
 if(ui.updateStatus)ui.updateStatus.textContent=status||"Verificando…";
};
function renderWorkouts(){
 ui.select.innerHTML="";
 const sorted=[...S.workouts].sort((a,b)=>{
  const today=new Date();today.setHours(0,0,0,0);
  const da=new Date(dateValue(a)||0).getTime(),db=new Date(dateValue(b)||0).getTime();
  const af=da>=today.getTime()&&!done(a),bf=db>=today.getTime()&&!done(b);
  if(af!==bf)return af?-1:1;return af?da-db:db-da;
 });
 if(!sorted.length){const o=document.createElement("option");o.textContent="Nenhum treino encontrado";o.value="";ui.select.appendChild(o);ui.prepare.disabled=true;ui.preview.textContent="O login existe, mas não há treino disponível neste UID.";return}
 sorted.slice(0,60).forEach(w=>{const o=document.createElement("option");o.value=w.source+":"+w.id;o.textContent=displayDate(dateValue(w))+" — "+titleOf(w)+(done(w)?" ✓":"");ui.select.appendChild(o)});
 const def=chooseDefault();if(def)ui.select.value=def.source+":"+def.id;selectWorkout(ui.select.value);ui.prepare.disabled=false;
}
function selectWorkout(value){
 const p=String(value||"").split(":"),src=p.shift(),id=p.join(":");
 S.selected=S.workouts.find(w=>w.source===src&&String(w.id)===id)||null;S.plan=null;show(ui.plan,false);ui.start.disabled=true;
 if(!S.selected){ui.preview.textContent="Selecione um treino.";return}
 const w=S.selected;
 ui.preview.textContent=[displayDate(dateValue(w))+" — "+titleOf(w),w.tipo?"Tipo: "+w.tipo:"",w.intensidade?"Intensidade: "+w.intensidade:"",w.distanciaSugerida?"Distância/meta: "+w.distanciaSugerida:"",descOf(w)].filter(Boolean).join("\n\n");
}
function parsePace(s){
 const m=String(s||"").match(/(\d{1,2})[:'](\d{2})(?:["”])?(?:\s*\/?\s*km)?/i);if(!m)return null;
 const v=Number(m[1])*60+Number(m[2]);return v>=120&&v<=900?v:null;
}
function parseDuration(s){
 const t=String(s||"");let m=t.match(/(\d{1,3})\s*(?:min|minuto|minutos)\b/i);if(m)return Number(m[1])*60;
 m=t.match(/(\d{1,2})[:h](\d{2})\b/i);if(m)return Number(m[1])*60+Number(m[2]);return null;
}
function parseDistance(s){
 const t=String(s||"");let m=t.match(/(\d+(?:[.,]\d+)?)\s*km\b/i);if(m)return Math.round(Number(m[1].replace(",","."))*1000);
 m=t.match(/(\d{2,5})\s*m(?:etros?)?\b/i);return m?Number(m[1]):null;
}
function rangeFromText(s){
 const t=String(s||"");const rx=/(\d{1,2})[:'](\d{2})\s*(?:a|até|-)\s*(\d{1,2})[:'](\d{2})(?:\s*\/?\s*km)?/i,m=t.match(rx);
 if(m){let a=Number(m[1])*60+Number(m[2]),b=Number(m[3])*60+Number(m[4]);if(a>b)[a,b]=[b,a];if(a>=120&&b<=900)return [a,b]}
 const p=parsePace(t);return p?[p-8,p+8]:null;
}
function validBlock(b){
 if(!b||typeof b!=="object")return null;
 const duration=Number(b.durationSec||b.durationSeconds||b.seconds||0)||null;
 const dist=Number(b.distanceM||b.distanceMeters||0)||null;
 let min=Number(b.targetPaceMinSec||b.paceMinSec||0)||null,max=Number(b.targetPaceMaxSec||b.paceMaxSec||0)||null;
 if(!(min&&max)){const r=rangeFromText(b.targetPace||b.pace||b.alvo||b.notes||"");if(r){min=r[0];max=r[1]}}
 if(!(duration||dist))return null;
 return {label:String(b.label||b.name||b.nome||"Bloco"),durationSec:duration,distanceM:dist,targetPaceMinSec:min,targetPaceMaxSec:max};
}
function explicitPlan(w){
 const raw=w&&(w.livePlan||w.atletiaLive||w.structuredWorkout),arr=Array.isArray(raw)?raw:(raw&&Array.isArray(raw.blocks)?raw.blocks:null);
 if(!arr)return null;const blocks=arr.map(validBlock).filter(Boolean);return blocks.length?{title:titleOf(w),blocks}:null;
}
function intervalPlan(w){
 const text=norm(descOf(w));if(!text)return null;const blocks=[];
 const warm=text.match(/(?:aquec(?:imento)?|aqueça)[^.;\n]{0,100}/i);if(warm){const duration=parseDuration(warm[0]),range=rangeFromText(warm[0]);if(duration)blocks.push({label:"Aquecimento",durationSec:duration,distanceM:null,targetPaceMinSec:range&&range[0],targetPaceMaxSec:range&&range[1]})}
 const rx=/(\d{1,2})\s*x\s*([^.;\n]{1,120})/ig;let m;
 while((m=rx.exec(text))){
  const reps=Math.min(30,Number(m[1])),seg=m[2],distance=parseDistance(seg),duration=parseDuration(seg),range=rangeFromText(seg);
  if(!(distance||duration)||!range)continue;
  const after=text.slice(m.index+m[0].length,m.index+m[0].length+180);
  const rec=after.match(/(?:rec(?:upera[cç][aã]o)?|trote|caminhada|intervalo)[^.;\n]{0,110}/i);
  const recDuration=rec&&parseDuration(rec[0]),recDistance=rec&&parseDistance(rec[0]),recRange=rec&&rangeFromText(rec[0]);
  for(let i=0;i<reps;i++){blocks.push({label:"Tiro "+(i+1)+"/"+reps,durationSec:duration,distanceM:distance,targetPaceMinSec:range[0],targetPaceMaxSec:range[1]});if(i<reps-1&&(recDuration||recDistance))blocks.push({label:"Recuperação",durationSec:recDuration,distanceM:recDistance,targetPaceMinSec:recRange&&recRange[0],targetPaceMaxSec:recRange&&recRange[1]})}
 }
 const cool=text.match(/(?:desaquec(?:imento)?|volta à calma|volta a calma)[^.;\n]{0,100}/i);if(cool){const duration=parseDuration(cool[0]),range=rangeFromText(cool[0]);if(duration)blocks.push({label:"Desaquecimento",durationSec:duration,distanceM:null,targetPaceMinSec:range&&range[0],targetPaceMaxSec:range&&range[1]})}
 return blocks.length?{title:titleOf(w),blocks}:null;
}
function steadyPlan(w){
 const text=norm([titleOf(w),descOf(w),w&&w.distanciaSugerida,w&&w.intensidade].filter(Boolean).join(" "));
 const range=rangeFromText(text),duration=parseDuration(text),distance=parseDistance(text);
 if(!range||!(duration||distance))return null;
 return {title:titleOf(w),blocks:[{label:"Treino contínuo",durationSec:duration,distanceM:distance,targetPaceMinSec:range[0],targetPaceMaxSec:range[1]}]};
}
function makePlan(w){return explicitPlan(w)||intervalPlan(w)||steadyPlan(w)}
function prepareWorkout(){
 message(ui.prepareMsg,"Analisando…");
 const plan=makePlan(S.selected);
 if(!plan||!plan.blocks.length){S.plan=null;show(ui.plan,false);message(ui.prepareMsg,"Não encontrei estrutura segura com duração/distância e pace explícitos. O app não vai inventar o treino.","warn");return}
 S.plan=plan;ui.planTitle.textContent=plan.title;ui.planBlocks.innerHTML="";
 plan.blocks.forEach((b,i)=>{const div=document.createElement("div");const meta=b.durationSec?fmtTime(b.durationSec):Math.round(b.distanceM)+" m";const pace=b.targetPaceMinSec&&b.targetPaceMaxSec?fmtPace(b.targetPaceMinSec)+" – "+fmtPace(b.targetPaceMaxSec):"Ritmo livre";div.className="block";div.innerHTML='<div class="block-num">'+(i+1)+'</div><div><div class="block-title">'+esc(b.label)+'</div><div class="block-sub">'+esc(pace)+'</div></div><div class="block-time">'+esc(meta)+'</div>';ui.planBlocks.appendChild(div)});
 show(ui.plan,true);ui.start.disabled=false;message(ui.prepareMsg,"Treino pronto para executar.","good");
 if(native())ncall("configure",JSON.stringify({version:"atletia.workout.v1",athleteUid:S.user.uid,athleteName:(S.athlete&&(S.athlete.name||S.athlete.nome))||"Thamis",title:plan.title,blocks:plan.blocks}));
}
async function testMibro(){
 message(ui.mibroMsg,"Verificando a ponte…");
 if(native()){
  const s=renderNative();
  if(!s.mibroFitInstalled||!s.notificationPermission||!s.bridgeAccess){
   message(ui.mibroMsg,"A ponte ainda não está liberada. Abrindo a configuração necessária.","warn");
   ncall("fixMibroBridge");
   return;
  }
  ncall("testAlert");
  message(ui.mibroMsg,"Enviei 2 alertas reais. A ponte só está confirmada quando o GS Pro vibrar/mostrar a mensagem.","warn");
  return
 }
 if(!("Notification" in window)){message(ui.mibroMsg,"Teste completo no relógio exige o APK Android.","warn");return}
 const p=Notification.permission==="default"?await Notification.requestPermission():Notification.permission;
 if(p==="granted"){new Notification("atletIA Mibro • TESTE",{body:"Se este aviso apareceu no GS Pro, a ponte de notificações está funcionando."});message(ui.mibroMsg,"Notificação enviada. No APK o teste também usa o serviço nativo.","good")}else message(ui.mibroMsg,"Permissão de notificação não concedida.","bad");
}
function activeBlock(){return S.plan&&S.plan.blocks[S.block]}
function announceBlock(b){if(!b)return;const target=b.targetPaceMinSec&&b.targetPaceMaxSec?" Alvo "+fmtPace(b.targetPaceMinSec)+" a "+fmtPace(b.targetPaceMaxSec)+".":" Ritmo livre.";speak(b.label+"."+target);vibrate([220,90,220])}
function setTarget(b){ui.phase.textContent=b?b.label:"—";ui.target.textContent=b&&b.targetPaceMinSec&&b.targetPaceMaxSec?"Alvo "+fmtPace(b.targetPaceMinSec)+" – "+fmtPace(b.targetPaceMaxSec):"Ritmo livre";ui.blockIndex.textContent=b?(S.block+1)+"/"+S.plan.blocks.length:"—"}
function startLive(){
 if(!S.plan)return;S.running=true;S.paused=false;S.block=0;S.distanceM=0;S.blockStartDistanceM=0;S.blockStarted=Date.now();S.lastPos=null;S.samples=[];S.paceSec=NaN;S.guide="unknown";S.lastGuideAt=0;
 show(ui.live,true);ui.live.scrollIntoView({behavior:"smooth",block:"start"});setTarget(activeBlock());setGuide("INICIANDO","good");
 if(native()){setGuide("AGUARDANDO PACE…","");ncall("configure",JSON.stringify({version:"atletia.workout.v1",athleteUid:S.user.uid,athleteName:(S.athlete&&(S.athlete.name||S.athlete.nome))||"Thamis",title:S.plan.title,blocks:S.plan.blocks}));ncall("start");return}
 if(!navigator.geolocation){message(ui.prepareMsg,"Este aparelho não disponibiliza GPS.","bad");return}
 S.watchId=navigator.geolocation.watchPosition(onPosition,()=>{ui.gpsBadge.textContent="GPS sem sinal";ui.gpsBadge.className="badge warn"},{enableHighAccuracy:true,maximumAge:1000,timeout:12000});
 announceBlock(activeBlock());
}
function onPosition(pos){
 if(!S.running||S.paused)return;const c=pos.coords,acc=Number(c.accuracy);
 if(!Number.isFinite(acc)||acc>GPS_MAX){ui.gpsBadge.textContent="GPS impreciso";ui.gpsBadge.className="badge warn";return}
 const now=pos.timestamp||Date.now();S.accuracyM=acc;ui.accuracy.textContent=Math.round(acc)+" m";ui.gpsBadge.textContent="GPS OK";ui.gpsBadge.className="badge good";
 if(S.lastPos){const d=haversine(S.lastPos.lat,S.lastPos.lon,c.latitude,c.longitude),dt=(now-S.lastPos.t)/1000;if(d>=0.8&&d<=Math.max(45,dt*12))S.distanceM+=d}
 S.lastPos={lat:c.latitude,lon:c.longitude,t:now};ui.distance.textContent=(S.distanceM/1000).toFixed(2).replace(".",",")+" km";
 if(Number.isFinite(c.speed))addSpeed(c.speed,now);
}
function haversine(a,b,c,d){const R=6371000,r=x=>x*Math.PI/180,dp=r(c-a),dl=r(d-b),x=Math.sin(dp/2)**2+Math.cos(r(a))*Math.cos(r(c))*Math.sin(dl/2)**2;return 2*R*Math.atan2(Math.sqrt(x),Math.sqrt(1-x))}
function addSpeed(speed,now){if(!Number.isFinite(speed)||speed<0.6||speed>8.5)return;S.samples.push({speed,time:now});S.samples=S.samples.filter(s=>s.time>=now-WINDOW_MS);if(S.samples.length<2)return;let sum=0,w=0;S.samples.forEach((s,i)=>{const k=i+1;sum+=s.speed*k;w+=k});const avg=sum/w;if(avg>0)S.paceSec=1000/avg;ui.pace.textContent=fmtPace(S.paceSec)}
function browserTick(){
 if(!S.running||S.paused||native())return;const b=activeBlock();if(!b)return;const now=Date.now(),elapsed=(now-S.blockStarted)/1000;let rem=null;
 if(b.durationSec){rem=Math.max(0,b.durationSec-elapsed);if(rem<=0){enterBrowserBlock(S.block+1);return}}
 else if(b.distanceM){rem=Math.max(0,b.distanceM-(S.distanceM-S.blockStartDistanceM));if(rem<=0){enterBrowserBlock(S.block+1);return}}
 ui.timerLabel.textContent=b.durationSec?"TEMPO RESTANTE":"DISTÂNCIA RESTANTE";ui.timer.textContent=b.durationSec?fmtTime(rem):Math.round(rem)+" m";evaluatePace(b,now);
}
function enterBrowserBlock(i){if(i>=S.plan.blocks.length){stopLive(false,true);return}S.block=i;S.blockStarted=Date.now();S.blockStartDistanceM=S.distanceM;S.guide="unknown";S.guideSince=Date.now();S.lastGuideAt=0;const b=activeBlock();setTarget(b);announceBlock(b)}
function evaluatePace(b,now){
 if(!Number.isFinite(S.paceSec)||!Number.isFinite(S.accuracyM)||S.accuracyM>GPS_MAX)return;
 const min=Number(b.targetPaceMinSec),max=Number(b.targetPaceMaxSec);if(!(min>0&&max>0)){setGuide("RITMO LIVRE","");return}
 let next="good";if(S.paceSec<min-HYST)next="fast";else if(S.paceSec>max+HYST)next="slow";
 if(next!==S.guide){S.guide=next;S.guideSince=now;return}if(now-S.guideSince<HOLD_MS)return;
 const cd=next==="good"?GOOD_COOLDOWN:OUT_COOLDOWN;if(now-S.lastGuideAt<cd)return;S.lastGuideAt=now;
 if(next==="fast"){setGuide("REDUZA","fast");speak("Rápido demais. Reduza um pouco.");vibrate([350,120,200])}
 else if(next==="slow"){setGuide("ACELERE","slow");speak("Acelere um pouco.");vibrate([180,100,180])}
 else{setGuide("MANTENHA","good");speak("Ritmo certo. Mantenha.");vibrate(160)}
}
function pauseLive(){
 if(!S.running)return;if(native()){ncall(S.paused?"resume":"pause");return}
 if(!S.paused){S.paused=true;S.pauseStarted=Date.now();ui.pause.textContent="CONTINUAR";setGuide("PAUSADO","")}
 else{S.blockStarted+=Date.now()-S.pauseStarted;S.paused=false;ui.pause.textContent="PAUSAR";setGuide("RETOMADO","good")}
}
function nextBlock(){if(native()){ncall("next");return}enterBrowserBlock(S.block+1)}
function stopLive(user=true,completed=false){
 if(native()&&user)ncall("stop");
 if(S.watchId!==null){navigator.geolocation.clearWatch(S.watchId);S.watchId=null}
 S.running=false;S.paused=false;
 if(completed){setGuide("TREINO CONCLUÍDO","good");speak("Treino concluído.")}
 else if(user){setGuide("TREINO ENCERRADO","warn");speak("Treino encerrado.")}
 ui.pause.textContent="PAUSAR";
}
window.AtletIAMibroNativeTelemetry=payload=>{
 if(!payload||typeof payload!=="object")return;
 const mode=String(payload.state||"");
 if(mode==="RUNNING"||mode==="PAUSED"){S.running=true;S.paused=mode==="PAUSED";show(ui.live,true);ui.pause.textContent=S.paused?"CONTINUAR":"PAUSAR"}
 if(mode==="ENDED"){S.running=false;S.paused=false;setGuide(payload.completed?"TREINO CONCLUÍDO":"TREINO ENCERRADO",payload.completed?"good":"warn")}
 if(Number.isFinite(payload.paceSecPerKm))ui.pace.textContent=fmtPace(payload.paceSecPerKm);
 if(Number.isFinite(payload.distanceM))ui.distance.textContent=(payload.distanceM/1000).toFixed(2).replace(".",",")+" km";
 if(Number.isFinite(payload.accuracyM)){ui.accuracy.textContent=Math.round(payload.accuracyM)+" m";ui.gpsBadge.textContent=payload.accuracyM<=GPS_MAX?"GPS OK":"GPS impreciso";ui.gpsBadge.className="badge "+(payload.accuracyM<=GPS_MAX?"good":"warn")}
 if(Number.isFinite(payload.blockIndex)&&S.plan&&S.plan.blocks.length){S.block=payload.blockIndex;setTarget(S.plan.blocks[S.block])}
 if(Number.isFinite(payload.remainingSec)){ui.timerLabel.textContent="TEMPO RESTANTE";ui.timer.textContent=fmtTime(payload.remainingSec)}else if(Number.isFinite(payload.remainingM)){ui.timerLabel.textContent="DISTÂNCIA RESTANTE";ui.timer.textContent=Math.round(payload.remainingM)+" m"}
 if(payload.guide){const g=String(payload.guide).toUpperCase();setGuide(g,g==="MANTENHA"?"good":(g==="REDUZA"?"fast":(g==="ACELERE"?"slow":"")))}
};
async function handleUser(user){
 show(ui.boot,false);S.user=user||null;
 if(!user){show(ui.login,true);show(ui.athlete,false);show(ui.mibro,false);show(ui.workouts,false);show(ui.plan,false);show(ui.live,false);return}
 show(ui.login,false);
 try{
  const athlete=await readAthlete(user.uid);if(!athlete)throw new Error("Login válido, mas cadastro do atleta não foi localizado em /users/{uid}.");
  S.athlete=athlete;S.workouts=await loadWorkouts(user.uid);
  ui.athleteName.textContent=athlete.name||athlete.nome||"Thamis";ui.athleteEmail.textContent=athlete.email||user.email||"";ui.workoutCount.textContent=S.workouts.length;
  const next=chooseDefault();ui.nextDate.textContent=next?displayDate(dateValue(next)):"—";
  show(ui.athlete,true);show(ui.mibro,true);show(ui.workouts,true);renderNative();renderWorkouts();
 }catch(e){show(ui.login,true);message(ui.loginMsg,e.message,"bad")}
}
function bind(){
 ui.loginForm.addEventListener("submit",async e=>{e.preventDefault();message(ui.loginMsg,"Entrando…");try{await S.auth.signInWithEmailAndPassword(ui.email.value.trim(),ui.password.value)}catch(err){message(ui.loginMsg,err.message||"Falha no login.","bad")}});
 ui.logout.addEventListener("click",async()=>{if(S.running)stopLive(true);await S.auth.signOut()});
 ui.select.addEventListener("change",e=>selectWorkout(e.target.value));ui.prepare.addEventListener("click",prepareWorkout);ui.start.addEventListener("click",startLive);
 ui.pause.addEventListener("click",pauseLive);ui.next.addEventListener("click",nextBlock);ui.stop.addEventListener("click",()=>stopLive(true));ui.testMibro.addEventListener("click",testMibro);
 ui.fixMibro.addEventListener("click",()=>ncall("fixMibroBridge"));ui.openMibroFit.addEventListener("click",()=>ncall("openMibroFit"));
}
function boot(){
 bind();renderNative();
 if(native()){try{window.AtletIAUpdateStatus(window.AtletIANative.updateStatus())}catch(e){}}
 try{firebaseReady();S.auth.onAuthStateChanged(handleUser)}catch(e){show(ui.boot,false);show(ui.login,true);message(ui.loginMsg,e.message,"bad")}
 S.timer=setInterval(browserTick,500);
}
boot();
})();