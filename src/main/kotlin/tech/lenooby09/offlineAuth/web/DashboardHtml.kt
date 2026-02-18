package tech.lenooby09.offlineAuth.web

object DashboardHtml {

	val INDEX = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>OfflineAuth Dashboard</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:monospace;background:#111;color:#ccc;padding:1rem}
a{color:#58a6ff}
input,select{background:#222;color:#ccc;border:1px solid #444;padding:.4rem;font-family:monospace;font-size:.9rem}
input:focus{outline:none;border-color:#58a6ff}
button{background:#333;color:#ccc;border:1px solid #555;padding:.3rem .7rem;cursor:pointer;font-family:monospace;font-size:.85rem}
button:hover{background:#444}
button.danger{color:#f66}
button.primary{color:#58a6ff}
table{width:100%;border-collapse:collapse;margin-top:.5rem}
th,td{padding:.3rem .5rem;text-align:left;border-bottom:1px solid #333;font-size:.85rem}
th{color:#888;font-weight:normal;text-transform:uppercase;font-size:.75rem}
.error{color:#f66}
.success{color:#6f6}
.section{margin-bottom:1.5rem}
.section h2{font-size:1rem;color:#888;border-bottom:1px solid #333;padding-bottom:.3rem;margin-bottom:.5rem}
.stats{display:flex;gap:2rem;flex-wrap:wrap}
.stats span{color:#888}
.stats strong{color:#ccc}
.tabs{display:flex;gap:.5rem;margin-bottom:1rem}
.tabs button{border:none;border-bottom:2px solid transparent;background:none;color:#888;padding:.3rem .5rem}
.tabs button.active{color:#ccc;border-bottom-color:#58a6ff}
.panel{display:none}
.panel.active{display:block}
.modal-overlay{display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,.7);z-index:100;justify-content:center;align-items:center}
.modal-overlay.active{display:flex}
.modal{background:#1a1a1a;border:1px solid #444;padding:1.5rem;max-width:400px;width:90%}
.modal h3{margin-bottom:.75rem;font-size:.95rem}
.modal input{width:100%;margin-bottom:.5rem}
.modal .actions{display:flex;gap:.5rem;justify-content:flex-end;margin-top:.75rem}
.toast{position:fixed;bottom:1rem;right:1rem;padding:.5rem 1rem;font-size:.85rem;z-index:200;border:1px solid #444;background:#222}
.toast.success{border-color:#6f6;color:#6f6}
.toast.error{border-color:#f66;color:#f66}
.hidden{display:none}
#loginScreen{display:flex;justify-content:center;align-items:center;min-height:100vh}
#loginBox{border:1px solid #444;padding:2rem;width:300px}
#loginBox h1{font-size:1.1rem;margin-bottom:1rem;color:#888}
#loginBox input{width:100%;margin-bottom:.5rem}
#loginBox button{width:100%;margin-top:.5rem}
</style>
</head>
<body>

<div id="loginScreen">
<div id="loginBox">
<h1>OfflineAuth</h1>
<div class="error" id="loginError"></div>
<input type="text" id="loginUsername" placeholder="Username" autocomplete="username">
<input type="password" id="loginPassword" placeholder="Password" autocomplete="current-password">
<button onclick="login()">Login</button>
</div>
</div>

<div id="dashboard" class="hidden">
<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:1rem">
<span style="color:#888">OfflineAuth &mdash; <strong id="currentUser"></strong></span>
<button onclick="logout()">Logout</button>
</div>

<div class="section">
<h2>Stats</h2>
<div class="stats">
<div><span>Accounts:</span> <strong id="statAccounts">-</strong></div>
<div><span>Online:</span> <strong id="statOnline">-</strong></div>
<div><span>Sessions:</span> <strong id="statSessions">-</strong></div>
<div id="statInvitesWrap"><span>Invites:</span> <strong id="statInvites">-</strong></div>
<div id="statBansWrap"><span>Bans:</span> <strong id="statBans">-</strong></div>
</div>
</div>

<div id="adminContent" class="hidden">
<div class="tabs" id="tabsBar">
<button class="active" onclick="switchTab('accounts',this)">Accounts</button>
<button onclick="switchTab('invites',this)">Invites</button>
<button onclick="switchTab('sessions',this)">Sessions</button>
<button onclick="switchTab('bans',this)">Bans</button>
</div>

<div class="panel active" id="panel-accounts">
<div style="display:flex;justify-content:space-between;align-items:center">
<span style="color:#888">Accounts</span>
<button class="primary" onclick="showCreateAccount()">+ Create</button>
</div>
<table>
<thead><tr><th>Username</th><th>ID</th><th>Registered</th><th>UUIDs</th><th>Admin</th><th>Actions</th></tr></thead>
<tbody id="accountsTable"></tbody>
</table>
</div>

<div class="panel" id="panel-invites">
<div style="display:flex;justify-content:space-between;align-items:center">
<span style="color:#888">Invite Codes</span>
<button class="primary" onclick="showCreateInvite()">+ Generate</button>
</div>
<table>
<thead><tr><th>Code</th><th>Created By</th><th>Uses</th><th>Created</th><th>Actions</th></tr></thead>
<tbody id="invitesTable"></tbody>
</table>
</div>

<div class="panel" id="panel-sessions">
<span style="color:#888">Active Sessions</span>
<table>
<thead><tr><th>Username</th><th>IP</th><th>Expires</th></tr></thead>
<tbody id="sessionsTable"></tbody>
</table>
</div>

<div class="panel" id="panel-bans">
<span style="color:#888">Soft Bans</span>
<table>
<thead><tr><th>IP</th><th>Expires</th><th>Actions</th></tr></thead>
<tbody id="bansTable"></tbody>
</table>
</div>
</div>

<div id="userContent" class="hidden">
<p style="color:#888;margin-top:1rem">You are logged in as a regular user. Only basic stats are shown. Contact an admin to get dashboard permissions.</p>
</div>
</div>

<div class="modal-overlay" id="modalOverlay">
<div class="modal" id="modalContent"></div>
</div>

<script>
let token=localStorage.getItem('oa_token'),currentUser=localStorage.getItem('oa_user'),isAdmin=false;
if(token)checkSession();

async function checkSession(){
	try{const r=await api('GET','/api/me');if(r.ok){const d=await r.json();isAdmin=d.isAdmin;showDashboard(d.username);}else clearSession();}catch{clearSession();}
}
function clearSession(){token=null;currentUser=null;isAdmin=false;localStorage.removeItem('oa_token');localStorage.removeItem('oa_user');}

async function login(){
	const u=document.getElementById('loginUsername').value.trim(),p=document.getElementById('loginPassword').value;
	document.getElementById('loginError').textContent='';
	if(!u||!p){document.getElementById('loginError').textContent='Fill in all fields.';return;}
	try{
		const r=await fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u,password:p})});
		const d=await r.json();
		if(r.ok){token=d.token;currentUser=d.username;isAdmin=d.isAdmin;localStorage.setItem('oa_token',token);localStorage.setItem('oa_user',currentUser);showDashboard(d.username);}
		else{document.getElementById('loginError').textContent=d.error||'Login failed.';}
	}catch{document.getElementById('loginError').textContent='Connection error.';}
}

async function logout(){try{await api('POST','/api/logout');}catch{}clearSession();document.getElementById('loginScreen').style.display='flex';document.getElementById('dashboard').classList.add('hidden');}

function showDashboard(username){
	document.getElementById('loginScreen').style.display='none';
	document.getElementById('dashboard').classList.remove('hidden');
	document.getElementById('currentUser').textContent=username+(isAdmin?' (admin)':'');
	if(isAdmin){document.getElementById('adminContent').classList.remove('hidden');document.getElementById('userContent').classList.add('hidden');loadAccounts();loadInvites();loadSessions();loadBans();}
	else{document.getElementById('adminContent').classList.add('hidden');document.getElementById('userContent').classList.remove('hidden');}
	loadStats();
}

async function api(method,url,body){const o={method,headers:{'Authorization':'Bearer '+token}};if(body){o.headers['Content-Type']='application/json';o.body=JSON.stringify(body);}return fetch(url,o);}

async function loadStats(){
	try{const r=await api('GET','/api/stats');if(!r.ok)return;const d=await r.json();
	document.getElementById('statAccounts').textContent=d.totalAccounts;
	document.getElementById('statOnline').textContent=d.onlinePlayers;
	document.getElementById('statSessions').textContent=d.activeSessions;
	document.getElementById('statInvites').textContent=d.activeInvites;
	document.getElementById('statBans').textContent=d.activeBans;
	}catch{}
}

function switchTab(name,el){document.querySelectorAll('.tabs button').forEach(t=>t.classList.remove('active'));document.querySelectorAll('.panel').forEach(p=>p.classList.remove('active'));el.classList.add('active');document.getElementById('panel-'+name).classList.add('active');}

async function loadAccounts(){
	try{const r=await api('GET','/api/accounts');if(!r.ok)return;const accs=await r.json();const tb=document.getElementById('accountsTable');
	if(!accs.length){tb.innerHTML='<tr><td colspan="6" style="color:#888">No accounts.</td></tr>';return;}
	tb.innerHTML=accs.map(a=>'<tr><td><strong>'+esc(a.username)+'</strong></td><td style="font-size:.75rem">'+esc(a.id.substring(0,8))+'…</td><td>'+fmtDate(a.registeredAt)+'</td><td>'+a.linkedUUIDs.length+'</td><td>'+(a.isDashboardAdmin?'✓':'')+'</td><td>'+
	'<button onclick="showRename(\''+esc(a.id)+'\',\''+esc(a.username)+'\')">Rename</button> '+
	'<button onclick="showChangePw(\''+esc(a.id)+'\',\''+esc(a.username)+'\')">Password</button> '+
	'<button onclick="toggleAdmin(\''+esc(a.id)+'\','+(!a.isDashboardAdmin)+')">'+(a.isDashboardAdmin?'Revoke Admin':'Grant Admin')+'</button> '+
	'<button class="danger" onclick="deleteAccount(\''+esc(a.id)+'\',\''+esc(a.username)+'\')">Delete</button>'+
	'</td></tr>').join('');}catch{}
}

function showCreateAccount(){showModal('<h3>Create Account</h3><input id="newUser" placeholder="Username"><input id="newPw" type="password" placeholder="Password"><div class="actions"><button onclick="closeModal()">Cancel</button><button class="primary" onclick="createAccount()">Create</button></div>');}
async function createAccount(){const u=document.getElementById('newUser').value.trim(),p=document.getElementById('newPw').value;if(!u||!p){toast('Fill all fields.','error');return;}const r=await api('POST','/api/accounts',{username:u,password:p});const d=await r.json();if(r.ok){toast('Created.','success');closeModal();loadAccounts();loadStats();}else toast(d.error||'Failed.','error');}

async function deleteAccount(id,name){if(!confirm('Delete "'+name+'"?'))return;const r=await api('DELETE','/api/accounts/'+id);if(r.ok){toast('Deleted.','success');loadAccounts();loadStats();}else toast('Failed.','error');}

function showRename(id,cur){showModal('<h3>Rename</h3><input id="rnInput" placeholder="New username" value="'+esc(cur)+'"><div class="actions"><button onclick="closeModal()">Cancel</button><button class="primary" onclick="renameAccount(\''+id+'\')">Rename</button></div>');}
async function renameAccount(id){const n=document.getElementById('rnInput').value.trim();if(!n)return;const r=await api('PUT','/api/accounts/'+id+'/rename',{newUsername:n});const d=await r.json();if(r.ok){toast('Renamed.','success');closeModal();loadAccounts();}else toast(d.error||'Failed.','error');}

function showChangePw(id,name){showModal('<h3>Change Password</h3><p style="color:#888;margin-bottom:.5rem">'+esc(name)+'</p><input id="npInput" type="password" placeholder="New password"><div class="actions"><button onclick="closeModal()">Cancel</button><button class="primary" onclick="changePw(\''+id+'\')">Update</button></div>');}
async function changePw(id){const p=document.getElementById('npInput').value;if(!p)return;const r=await api('PUT','/api/accounts/'+id+'/password',{newPassword:p});const d=await r.json();if(r.ok){toast('Updated.','success');closeModal();}else toast(d.error||'Failed.','error');}

async function toggleAdmin(id,grant){const r=await api('PUT','/api/accounts/'+id+'/admin',{isAdmin:grant});if(r.ok){toast(grant?'Admin granted.':'Admin revoked.','success');loadAccounts();}else toast('Failed.','error');}

async function loadInvites(){
	try{const r=await api('GET','/api/invites');if(!r.ok)return;const inv=await r.json();const tb=document.getElementById('invitesTable');
	if(!inv.length){tb.innerHTML='<tr><td colspan="5" style="color:#888">No invites.</td></tr>';return;}
	tb.innerHTML=inv.map(i=>'<tr><td style="font-family:monospace">'+esc(i.code)+'</td><td>'+esc(i.createdBy)+'</td><td>'+i.currentUses+'/'+i.maxUses+'</td><td>'+fmtDate(i.createdAt)+'</td><td><button class="danger" onclick="revokeInvite(\''+esc(i.code)+'\')">Revoke</button></td></tr>').join('');}catch{}
}
function showCreateInvite(){showModal('<h3>Generate Invite</h3><input id="invMaxUses" type="number" min="1" value="1" placeholder="Max uses"><div class="actions"><button onclick="closeModal()">Cancel</button><button class="primary" onclick="createInvite()">Generate</button></div>');}
async function createInvite(){const m=parseInt(document.getElementById('invMaxUses').value)||1;const r=await api('POST','/api/invites',{maxUses:m});const d=await r.json();if(r.ok){toast('Code: '+d.code,'success');closeModal();loadInvites();loadStats();}else toast(d.error||'Failed.','error');}
async function revokeInvite(code){if(!confirm('Revoke "'+code+'"?'))return;const r=await api('DELETE','/api/invites/'+code);if(r.ok){toast('Revoked.','success');loadInvites();loadStats();}else toast('Failed.','error');}

async function loadSessions(){
	try{const r=await api('GET','/api/sessions');if(!r.ok)return;const ss=await r.json();const tb=document.getElementById('sessionsTable');
	if(!ss.length){tb.innerHTML='<tr><td colspan="3" style="color:#888">No sessions.</td></tr>';return;}
	tb.innerHTML=ss.map(s=>'<tr><td>'+esc(s.username)+'</td><td>'+esc(s.ipAddress)+'</td><td>'+fmtDate(s.expiresAt)+'</td></tr>').join('');}catch{}
}

async function loadBans(){
	try{const r=await api('GET','/api/bans');if(!r.ok)return;const bs=await r.json();const tb=document.getElementById('bansTable');
	if(!bs.length){tb.innerHTML='<tr><td colspan="3" style="color:#888">No bans.</td></tr>';return;}
	tb.innerHTML=bs.map(b=>'<tr><td>'+esc(b.ipAddress)+'</td><td>'+fmtDate(b.expiresAt)+'</td><td><button class="danger" onclick="removeBan(\''+esc(b.ipAddress)+'\')">Remove</button></td></tr>').join('');}catch{}
}
async function removeBan(ip){if(!confirm('Remove ban for '+ip+'?'))return;const r=await api('DELETE','/api/bans/'+ip);if(r.ok){toast('Removed.','success');loadBans();loadStats();}else toast('Failed.','error');}

function fmtDate(ts){return new Date(ts).toLocaleString();}
function esc(s){const d=document.createElement('div');d.textContent=s;return d.innerHTML;}
function showModal(h){document.getElementById('modalContent').innerHTML=h;document.getElementById('modalOverlay').classList.add('active');}
function closeModal(){document.getElementById('modalOverlay').classList.remove('active');}
document.getElementById('modalOverlay').addEventListener('click',function(e){if(e.target===this)closeModal();});
function toast(msg,type){const el=document.createElement('div');el.className='toast '+type;el.textContent=msg;document.body.appendChild(el);setTimeout(()=>el.remove(),3000);}
document.getElementById('loginPassword').addEventListener('keydown',function(e){if(e.key==='Enter')login();});
document.getElementById('loginUsername').addEventListener('keydown',function(e){if(e.key==='Enter')document.getElementById('loginPassword').focus();});
setInterval(()=>{if(token)loadStats();},30000);
</script>
</body>
</html>
""".trimIndent()
}
