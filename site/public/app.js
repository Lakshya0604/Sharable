const $=s=>document.querySelector(s);let mode='in';
const api=(p,b)=>fetch('/api/'+p,{method:b===undefined?'GET':'POST',headers:{'Content-Type':'application/json'},body:b===undefined?undefined:JSON.stringify(b)});
function view(u){$('#auth').hidden=!!u;$('#home').hidden=!u;$('#out').hidden=!u;if(u)$('#hi').textContent='Hi '+u.name+' ('+u.email+')'}
function tab(m){mode=m;$('#t1').className=m=='in'?'on':'';$('#t2').className=m=='up'?'on':'';$('#nm').hidden=m=='in';$('#go').textContent=m=='in'?'Log in':'Create account';$('#pw').autocomplete=m=='in'?'current-password':'new-password';$('#err').textContent=''}
$('#t1').onclick=()=>tab('in');$('#t2').onclick=()=>tab('up');
$('#f').onsubmit=async e=>{e.preventDefault();$('#err').textContent='';const b={email:$('#email').value,password:$('#pw').value};if(mode=='up')b.name=$('#name').value;
 const r=await api(mode=='in'?'login':'signup',b);if(!r.ok){$('#err').textContent=(await r.json()).error||'Failed';return}$('#pw').value='';load()};
$('#out').onclick=async()=>{await api('logout',{});view(null)};
async function load(){const r=await api('me');view(r.ok?await r.json():null)}load();
