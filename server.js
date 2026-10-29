const express=require('express'),PptxGenJS=require('pptxgenjs'),JSZip=require('jszip');
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const {PORT=3000,AI_BASE_URL='https://api.groq.com/openai/v1',AI_API_KEY='',AI_MODEL='llama-3.3-70b-versatile',APP_TOKEN=''}=process.env;
const OUT=path.join(__dirname,'out');fs.mkdirSync(OUT,{recursive:true});
const s=(v,n=200)=>String(v??'').slice(0,n);
const app=express();app.use(express.json({limit:'100kb'}));
app.use('/files',express.static(OUT,{setHeaders:r=>r.setHeader('Content-Disposition','attachment')}));
app.use('/api',(q,r,n)=>{if(APP_TOKEN&&q.get('x-app-token')!==APP_TOKEN)return r.status(401).json({error:'bad token'});n();});

async function llm(system,user){
  const r=await fetch(`${AI_BASE_URL}/chat/completions`,{method:'POST',
    headers:{'Content-Type':'application/json',Authorization:`Bearer ${AI_API_KEY}`},
    body:JSON.stringify({model:AI_MODEL,temperature:0.3,response_format:{type:'json_object'},
      messages:[{role:'system',content:system},{role:'user',content:user}]})});
  if(!r.ok)throw new Error(`AI provider HTTP ${r.status}`);
  const j=await r.json();
  return JSON.parse(j.choices[0].message.content.replace(/^```json|```$/g,'').trim());
}

// ---- command interpretation (AI output is validated against an allowlist; nothing is ever executed here) ----
const TYPES=['open_app','search','media','navigate','chat','presentation','unsupported'];
const CMD_SYS=`Convert a phone voice command into ONE JSON object with fields: type (open_app|search|media|navigate|chat|presentation|unsupported), app, query, command, text, topic, slides, audience.
open_app: app=app name. search: app="youtube" or "google", query. media: command=play|pause|next|previous|stop. navigate: command=home|back.
chat: text=short spoken answer. presentation: topic, slides (number), audience. Calls, SMS, payments, deleting data, shell or code: type=unsupported with text explaining. JSON only.`;
function validate(a){
  const t=TYPES.includes(a?.type)?a.type:'unsupported';
  const o={type:t,app:s(a?.app,60),query:s(a?.query,200),command:s(a?.command,20),text:s(a?.text,500),topic:s(a?.topic,150),
    slides:Math.min(20,Math.max(3,parseInt(a?.slides)||10)),audience:s(a?.audience,60)};
  if(t==='media'&&!['play','pause','next','previous','stop'].includes(o.command))return{type:'unsupported',text:'Unsupported media command.'};
  if(t==='navigate'&&!['home','back'].includes(o.command))return{type:'unsupported',text:'Unsupported navigation.'};
  if(t==='presentation')o.confirm=true;
  return o;
}
app.post('/api/command',async(q,r)=>{
  const text=s(q.body.text,500);
  if(!AI_API_KEY)return r.json({type:'chat',text:'No AI key is configured on the server, so only built-in commands work.'});
  try{r.json(validate(await llm(CMD_SYS,text)));}catch(e){r.status(502).json({error:e.message});}
});

// ---- presentation ----
const PPT_SYS=`Create a presentation outline as JSON: {"title","subtitle","slides":[{"type":"bullets|table|flow","title","bullets":[max 5 short strings],"table":{"header":[],"rows":[[]]},"steps":[3-5 short strings],"notes":"speaker notes"}]}.
Include introduction, logically ordered content, at least one example, one comparison table, one flow/timeline slide, a conclusion and a references slide. Only cite real, well-known sources and say "verify before submitting". Exactly the requested number of slides after the title slide.`;
function fallback(topic,n){
  const base=[
    {type:'bullets',title:'Introduction',bullets:[`What is ${topic}?`,'Why it matters','What this presentation covers'],notes:'Template slide: replace with real content.'},
    {type:'bullets',title:'Background',bullets:['Key history and milestones','Core concepts','Important terms'],notes:''},
    {type:'flow',title:'How It Works',steps:['Input','Process','Analysis','Output'],notes:''},
    {type:'bullets',title:'Key Ideas',bullets:['Idea 1','Idea 2','Idea 3'],notes:''},
    {type:'table',title:'Comparison',table:{header:['Aspect','Option A','Option B'],rows:[['Strength','—','—'],['Limitation','—','—']]},notes:''},
    {type:'bullets',title:'Examples',bullets:['Real-world example 1','Real-world example 2'],notes:''},
    {type:'flow',title:'Timeline',steps:['Past','Present','Near future','Long term'],notes:''},
    {type:'bullets',title:'Challenges',bullets:['Challenge 1','Challenge 2','Possible solutions'],notes:''},
    {type:'bullets',title:'Conclusion',bullets:['Key takeaways','Next steps'],notes:''},
    {type:'bullets',title:'References',bullets:['Add your sources here (no AI key configured: this is a template outline)'],notes:''}];
  return{title:topic,subtitle:'Template outline (no AI key configured)',slides:base.slice(0,n)};
}
function normalize(o,n){
  const slides=(Array.isArray(o.slides)?o.slides:[]).slice(0,n).map(x=>({
    type:['bullets','table','flow'].includes(x.type)?x.type:'bullets',title:s(x.title,80),
    bullets:(x.bullets||[]).slice(0,6).map(b=>s(b,160)),steps:(x.steps||[]).slice(0,5).map(b=>s(b,40)),
    table:x.table&&Array.isArray(x.table.header)?{header:x.table.header.slice(0,5).map(h=>s(h,40)),rows:(x.table.rows||[]).slice(0,6).map(r=>r.slice(0,5).map(c=>s(c,60)))}:null,
    notes:s(x.notes,800)}));
  return{title:s(o.title,100)||'Presentation',subtitle:s(o.subtitle,120),slides};
}
async function buildDeck(o,file){
  const p=new PptxGenJS();p.layout='LAYOUT_WIDE';p.title=o.title;
  const NAVY='1F2A44',BLUE='2E86DE',LIGHT='F5F7FA',F='Calibri';
  const t=p.addSlide();t.background={color:NAVY};
  t.addText(o.title,{x:.8,y:2.2,w:11.7,h:1.5,fontFace:F,fontSize:40,bold:true,color:'FFFFFF'});
  t.addShape(p.ShapeType.rect,{x:.8,y:3.75,w:2,h:.06,fill:{color:BLUE}});
  t.addText(o.subtitle,{x:.8,y:3.95,w:11.7,h:.8,fontFace:F,fontSize:20,color:'A9C7F0'});
  for(const x of o.slides){
    const sl=p.addSlide();sl.background={color:LIGHT};
    sl.addShape(p.ShapeType.rect,{x:0,y:0,w:13.33,h:1.1,fill:{color:NAVY}});
    sl.addText(x.title,{x:.6,y:.15,w:12,h:.8,fontFace:F,fontSize:28,bold:true,color:'FFFFFF'});
    if(x.type==='table'&&x.table){
      const rows=[x.table.header.map(h=>({text:h,options:{bold:true,color:'FFFFFF',fill:{color:BLUE}}})),...x.table.rows.map(r=>r.map(c=>({text:c})))];
      sl.addTable(rows,{x:.6,y:1.6,w:12.1,fontFace:F,fontSize:16,color:'222222',border:{type:'solid',color:'CCCCCC',pt:1}});
    }else if(x.type==='flow'&&x.steps.length){
      const n=x.steps.length,w=(12.1-(n-1)*.6)/n;
      x.steps.forEach((st,i)=>{const px=.6+i*(w+.6);
        sl.addText(st,{shape:p.ShapeType.roundRect,x:px,y:2.8,w,h:1.5,fill:{color:BLUE},color:'FFFFFF',fontFace:F,fontSize:18,align:'center',valign:'middle'});
        if(i<n-1)sl.addShape(p.ShapeType.rightArrow,{x:px+w+.1,y:3.3,w:.4,h:.5,fill:{color:NAVY}});});
    }else{
      sl.addText(x.bullets.map(b=>({text:b,options:{bullet:true,breakLine:true}})),{x:.7,y:1.5,w:11.9,h:5,fontFace:F,fontSize:22,color:'222222',valign:'top',paraSpaceAfter:12});
    }
    sl.slideNumber={x:12.4,y:7,fontSize:10,color:'888888'};
    sl.addNotes(x.notes||'');
  }
  await p.writeFile({fileName:file});
  // verify the file is a real PPTX with the expected slide count
  const z=await JSZip.loadAsync(fs.readFileSync(file));
  const n=Object.keys(z.files).filter(k=>/^ppt\/slides\/slide\d+\.xml$/.test(k)).length;
  if(!z.files['ppt/presentation.xml']||n!==o.slides.length+1)throw new Error('Generated PPTX failed validation');
}
app.post('/api/presentation',async(q,r)=>{
  const topic=s(q.body.topic,150)||'General topic',n=Math.min(20,Math.max(3,parseInt(q.body.slides)||10)),aud=s(q.body.audience,60)||'college';
  try{
    let o=fallback(topic,n);
    if(AI_API_KEY)o=await llm(PPT_SYS,`Topic: ${topic}\nAudience: ${aud}\nSlides after title: ${n}`);
    o=normalize(o,n);if(!o.slides.length)throw new Error('Empty outline');
    const id=crypto.randomBytes(12).toString('hex');
    await buildDeck(o,path.join(OUT,`${id}.pptx`));
    r.json({title:o.title,slides:o.slides.map(x=>({title:x.title,type:x.type})),downloadUrl:`/files/${id}.pptx`});
  }catch(e){r.status(500).json({error:e.message});}
});
app.get('/health',(q,r)=>r.json({ok:true,ai:!!AI_API_KEY}));
app.listen(PORT,'0.0.0.0',()=>console.log(`MD backend on :${PORT} (AI ${AI_API_KEY?'on':'off'})`));
