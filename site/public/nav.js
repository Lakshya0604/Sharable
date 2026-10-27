fetch('/api/me').then(r => { if (r.ok) document.getElementById('navbtn').textContent = 'My account'; }).catch(() => {});
