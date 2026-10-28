'use strict';

const page = document.body.dataset.legalPage;
const statusBox = document.querySelector('#legal-status');
const errorBox = document.querySelector('#legal-error');
const token = new URLSearchParams(location.hash.replace(/^#/, '')).get('token') || '';
if (token) {
  // Do not leave the token in the address bar or browser history.
  history.replaceState(null, '', location.pathname);
}

function show(message, tone = 'default') {
  if (!statusBox) return;
  statusBox.textContent = message;
  statusBox.dataset.tone = tone;
  statusBox.hidden = false;
}

async function post(path, body) {
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const data = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) {
    const message = typeof data?.detail === 'string' ? data.detail : 'No pudimos completar la acción.';
    throw new Error(message);
  }
  return data;
}

if (page === 'verify') {
  if (!token) {
    show('Falta el token de verificación. Solicita un enlace nuevo desde el panel.', 'error');
  } else {
    post('/api/v1/auth/verify-email', { token })
      .then(() => show('Correo verificado. Ya puedes entrar al panel.', 'ok'))
      .catch(() => show('El enlace no es válido o expiró. Solicita uno nuevo desde el panel.', 'error'));
  }
}

if (page === 'recover') {
  const form = document.querySelector('#recover-form');
  const emailInput = form?.querySelector('[name="email"]');
  const resendButton = document.querySelector('#resend-verification');

  function readEmail() {
    const email = String(emailInput?.value || '').trim().toLowerCase();
    if (!email.includes('@')) {
      throw new Error('Escribe un correo válido.');
    }
    return email;
  }

  form?.addEventListener('submit', async (event) => {
    event.preventDefault();
    errorBox.textContent = '';
    try {
      await post('/api/v1/auth/password/forgot', { email: readEmail() });
      show('Si el correo existe, enviamos instrucciones para restablecer la contraseña. Revisa también la carpeta de spam.', 'ok');
    } catch (error) {
      errorBox.textContent = error.message;
    }
  });

  resendButton?.addEventListener('click', async () => {
    errorBox.textContent = '';
    try {
      await post('/api/v1/auth/verify-email/resend', { email: readEmail() });
      show('Si corresponde, enviamos un nuevo enlace de verificación.', 'ok');
    } catch (error) {
      errorBox.textContent = error.message;
    }
  });
}

if (page === 'reset') {
  document.querySelector('#reset-form')?.addEventListener('submit', async (event) => {
    event.preventDefault();
    errorBox.textContent = '';
    const data = new FormData(event.target);
    const password = String(data.get('password') || '');
    const password2 = String(data.get('password2') || '');
    if (password.length < 12) {
      errorBox.textContent = 'La contraseña debe tener al menos 12 caracteres.';
      return;
    }
    if (password !== password2) {
      errorBox.textContent = 'Las contraseñas no coinciden.';
      return;
    }
    if (!token) {
      errorBox.textContent = 'Falta el token de restablecimiento.';
      return;
    }
    try {
      await post('/api/v1/auth/password/reset', { token, new_password: password });
      event.target.hidden = true;
      show('Contraseña actualizada. Ya puedes entrar con tu nueva clave.', 'ok');
    } catch (error) {
      errorBox.textContent = error.message;
    }
  });
}
