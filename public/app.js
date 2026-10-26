const $=s=>document.querySelector(s);let PIN=sessionStorage.getItem('pin')||'';{const m=/pin=(\d+)/.exec(location.hash);if(m){PIN=m[1];history.replaceState(null,'',location.pathname)}}const CH=32*1024*1024;
const H=()=>({'X-Pin':PIN});const fmt=n=>n>1e9?(n/1e9).toFixed(2)+' GB':n>1e6?(n/1e6).toFixed(1)+' MB':(n/1e3).toFixed(0)+' KB';
async function connect(p){PIN=p;const r=await fetch('/api/files',{headers:H()});if(!r.ok){$('#gerr').textContent=r.status===401?'Wrong PIN':'Error '+r.status;return false}
sessionStorage.setItem('pin',p);$('#gate').hidden=true;$('#app').hidden=false;list(await r.json());return true}
function list(a){const ul=$('#list');ul.innerHTML='';if(!a.length)ul.innerHTML='<li><small>No files yet.</small></li>';
a.sort((x,y)=>y.mtime-x.mtime).forEach(f=>{const li=document.createElement('li');const s=document.createElement('span');s.textContent=f.name+' ';const sm=document.createElement('small');sm.textContent=fmt(f.size);s.append(sm);
const l=document.createElement('a');l.textContent='Download';l.href='/f/'+encodeURIComponent(f.name)+'?pin='+encodeURIComponent(PIN);l.setAttribute('download',f.name);li.append(s,l);ul.append(li)})}
async function refresh(){const r=await fetch('/api/files',{headers:H()});if(r.ok)list(await r.json())}
const hex=b=>[...new Uint8Array(b)].map(x=>x.toString(16).padStart(2,'0')).join('');
async function upload(file){const box=document.createElement('div');box.className='up';box.innerHTML='<b></b><div class="bar"><i></i></div><small></small>';$('#ups').append(box);
box.querySelector('b').textContent=file.name;const bar=box.querySelector('i'),st=box.querySelector('small');const name=encodeURIComponent(file.name);
try{const key=(await sha256Buf(new TextEncoder().encode(`${file.name}|${file.size}|${file.lastModified}`).buffer)).slice(0,32);const q=`key=${key}&size=${file.size}`;
let off=0;{const si=await (await fetch(`/api/session?${q}&name=${name}`,{headers:H()})).json();st.textContent='Checking earlier progress against this file...';
for(const p of si.pieces){const hh=await sha256Buf(await file.slice(p.off,p.off+p.len).arrayBuffer());if(hh!==p.sha)break;off=p.off+p.len}}
const t0=performance.now(),base=off;
while(off<file.size||file.size===0){const end=Math.min(off+CH,file.size),fin=end>=file.size;const slice=file.slice(off,end);
const sha=await sha256Buf(await slice.arrayBuffer());let r;
for(let a=0;a<3;a++){r=await fetch(`/u/${name}?${q}&offset=${off}${fin?'&final=1':''}`,{method:'PUT',headers:{...H(),'X-Sha256':sha},body:slice});if(r.status!==422)break}
if(r.status===409){off=(await r.json()).offset;continue}if(!r.ok)throw new Error('HTTP '+r.status);off=end;
bar.style.width=(file.size?off/file.size*100:100)+'%';const mbs=(off-base)/1e6/((performance.now()-t0)/1000||1);st.textContent=`${fmt(off)} of ${fmt(file.size)} - ${mbs.toFixed(1)} MB/s`;if(fin)break}
st.textContent='Done: '+fmt(file.size)+' sent, every piece verified';refresh()}catch(e){st.textContent='Interrupted ('+e.message+'). Choose the same file again to continue.'}}
$('#pinform').onsubmit=e=>{e.preventDefault();connect($('#pin').value.trim())};
$('#file').onchange=async e=>{for(const f of e.target.files)await upload(f);e.target.value=''};$('#refresh').onclick=refresh;
const d=$('#drop');['dragover','dragenter'].forEach(v=>d.addEventListener(v,e=>{e.preventDefault();d.classList.add('over')}));['dragleave','drop'].forEach(v=>d.addEventListener(v,e=>{e.preventDefault();d.classList.remove('over')}));
d.addEventListener('drop',async e=>{for(const f of e.dataTransfer.files)await upload(f)});if(PIN)connect(PIN);
