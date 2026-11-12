from html import escape


def _layout(
    *,
    preheader: str,
    eyebrow: str,
    title: str,
    alias: str,
    intro: str,
    cta_label: str,
    link: str,
    expiry: str,
    notice: str,
    footer_reason: str,
    base_url: str,
) -> str:
    safe_alias = escape(alias or "tutor/a")
    safe_link = escape(link, quote=True)
    safe_link_text = escape(link)
    safe_base = escape(base_url.rstrip("/"), quote=True)
    safe_preheader = escape(preheader)
    safe_eyebrow = escape(eyebrow)
    safe_title = escape(title)
    safe_intro = escape(intro)
    safe_cta = escape(cta_label)
    safe_expiry = escape(expiry)
    safe_notice = escape(notice)
    safe_footer_reason = escape(footer_reason)

    return f"""<!doctype html>
<html lang="es-CL">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light">
  <title>{safe_title}</title>
  <style>
    @media only screen and (max-width: 620px) {{
      .mail-wrap {{ padding: 16px 8px !important; }}
      .mail-header {{ padding: 22px 20px !important; }}
      .mail-body {{ padding: 26px 20px !important; }}
      .mail-title {{ font-size: 24px !important; }}
      .mail-button a {{ display: block !important; text-align: center !important; }}
      .mail-footer {{ padding: 18px 20px !important; }}
    }}
  </style>
</head>
<body style="margin:0;padding:0;background:#eef2ed;color:#10201b;">
  <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;">{safe_preheader}</div>
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#eef2ed;">
    <tr>
      <td class="mail-wrap" align="center" style="padding:32px 12px;">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="max-width:600px;width:100%;">
          <tr>
            <td class="mail-header" style="background:#081b17;padding:28px 32px;border-radius:18px 18px 0 0;">
              <table role="presentation" cellpadding="0" cellspacing="0" border="0">
                <tr>
                  <td width="44" valign="middle" style="padding-right:12px;">
                    <table role="presentation" cellpadding="0" cellspacing="0" border="0">
                      <tr>
                        <td width="44" height="44" align="center" valign="middle" style="width:44px;height:44px;background:#123029;border:1px solid #2b4438;border-radius:10px;font-family:'Courier New',Consolas,monospace;font-size:12px;font-weight:700;color:#a9c6b8;letter-spacing:1px;">IA</td>
                      </tr>
                    </table>
                  </td>
                  <td valign="middle">
                    <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;font-size:17px;font-weight:700;color:#eef2ed;line-height:1.2;">IA Guardian</div>
                    <div style="font-family:'Courier New',Consolas,monospace;font-size:9px;letter-spacing:1.5px;text-transform:uppercase;color:#a9c6b8;margin-top:4px;">Privacidad por diseño</div>
                  </td>
                </tr>
              </table>
            </td>
          </tr>
          <tr>
            <td class="mail-body" style="background:#fbfcfa;border:1px solid #d7e0d9;border-top:0;border-radius:0 0 18px 18px;padding:34px 32px;">
              <div style="font-family:'Courier New',Consolas,monospace;font-size:10px;letter-spacing:1.5px;text-transform:uppercase;color:#1b4a3e;">{safe_eyebrow}</div>
              <h1 class="mail-title" style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;font-size:28px;line-height:1.15;letter-spacing:-0.5px;color:#10201b;margin:12px 0 18px;font-weight:700;">{safe_title}</h1>
              <p style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;font-size:15px;line-height:1.6;color:#344a42;margin:0 0 10px;">Hola, <strong>{safe_alias}</strong>.</p>
              <p style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;font-size:15px;line-height:1.6;color:#344a42;margin:0 0 24px;">{safe_intro}</p>
              <table role="presentation" class="mail-button" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 14px;">
                <tr>
                  <td style="background:#1b4a3e;border-radius:10px;">
                    <a href="{safe_link}" style="display:inline-block;padding:14px 22px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;font-size:15px;font-weight:600;line-height:1.2;color:#ffffff;text-decoration:none;border-radius:10px;">{safe_cta}</a>
                  </td>
                </tr>
              </table>
              <p style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;font-size:12px;line-height:1.5;color:#61756c;margin:0 0 24px;">{safe_expiry}</p>
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#f5f8f4;border:1px solid #d7e0d9;border-radius:12px;margin:0 0 22px;">
                <tr>
                  <td style="padding:14px 16px;">
                    <div style="font-family:'Courier New',Consolas,monospace;font-size:9px;letter-spacing:1.2px;text-transform:uppercase;color:#61756c;margin-bottom:6px;">Si el botón no funciona</div>
                    <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;font-size:12px;line-height:1.5;color:#344a42;word-break:break-all;"><a href="{safe_link}" style="color:#1b4a3e;text-decoration:underline;">{safe_link_text}</a></div>
                  </td>
                </tr>
              </table>
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#e5eee8;border:1px solid #d7e0d9;border-radius:12px;margin:0 0 24px;">
                <tr>
                  <td style="padding:14px 16px;">
                    <p style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;font-size:12.5px;line-height:1.6;color:#1b4a3e;margin:0;">{safe_notice}</p>
                  </td>
                </tr>
              </table>
              <p style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;font-size:12px;line-height:1.6;color:#61756c;margin:0;">{safe_footer_reason}</p>
            </td>
          </tr>
          <tr>
            <td class="mail-footer" style="padding:20px 12px 4px;text-align:center;">
              <p style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;font-size:11px;line-height:1.6;color:#61756c;margin:0;">IA Guardian · Privacidad por diseño</p>
              <p style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;font-size:10.5px;line-height:1.6;color:#7a8d83;margin:6px 0 0;">
                <a href="{safe_base}/legal/privacidad" style="color:#1b4a3e;text-decoration:underline;">Privacidad</a>
                &nbsp;·&nbsp;
                <a href="{safe_base}/legal/terminos" style="color:#1b4a3e;text-decoration:underline;">Términos</a>
                &nbsp;·&nbsp;
                <a href="{safe_base}/legal/seguridad" style="color:#1b4a3e;text-decoration:underline;">Seguridad</a>
              </p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>"""


def password_reset_email(alias: str, link: str, base_url: str) -> tuple[str, str, str]:
    subject = "Restablece tu contraseña de IA Guardian"
    text = (
        f"Hola, {alias or 'tutor/a'}.\n\n"
        "Recibimos una solicitud para cambiar la contraseña de tu cuenta de tutor. "
        "Crea una contraseña nueva con este enlace:\n\n"
        f"{link}\n\n"
        "El enlace vence en 1 hora y solo se puede usar una vez. "
        "Si no lo solicitaste, ignora este correo: tu contraseña actual sigue funcionando.\n\n"
        "Nadie de IA Guardian te pedirá tu contraseña por correo.\n"
    )
    html = _layout(
        preheader="Enlace para restablecer tu contraseña. Vence en 1 hora.",
        eyebrow="Seguridad de la cuenta",
        title="Restablece tu contraseña.",
        alias=alias,
        intro=(
            "Recibimos una solicitud para cambiar la contraseña de tu cuenta de tutor. "
            "Crea una contraseña nueva con el botón de abajo."
        ),
        cta_label="Crear contraseña nueva",
        link=link,
        expiry="El enlace vence en 1 hora y solo se puede usar una vez.",
        notice=(
            "Si no solicitaste este cambio, ignora este correo. Tu contraseña actual sigue funcionando. "
            "Nadie de IA Guardian te pedirá tu contraseña por correo."
        ),
        footer_reason="Recibiste este correo porque se solicitó un cambio de contraseña en tu cuenta de tutor.",
        base_url=base_url,
    )
    return subject, text, html


def verification_email(alias: str, link: str, base_url: str) -> tuple[str, str, str]:
    subject = "Verifica tu correo en IA Guardian"
    text = (
        f"Hola, {alias or 'tutor/a'}.\n\n"
        "Confirma tu correo para activar tu cuenta de tutor:\n\n"
        f"{link}\n\n"
        "El enlace vence en 24 horas. Si no creaste esta cuenta, ignora este mensaje.\n"
    )
    html = _layout(
        preheader="Confirma tu correo para activar tu cuenta de tutor.",
        eyebrow="Tu cuenta de tutor",
        title="Confirma tu correo.",
        alias=alias,
        intro=(
            "Solo falta un paso para activar tu cuenta de tutor y empezar a cuidar con señales, "
            "no con conversaciones."
        ),
        cta_label="Verificar mi correo",
        link=link,
        expiry="El enlace vence en 24 horas.",
        notice=(
            "Si no creaste esta cuenta, ignora este correo. No se activará nada hasta que confirmes tu correo."
        ),
        footer_reason="Recibiste este correo porque se creó una cuenta de tutor con esta dirección.",
        base_url=base_url,
    )
    return subject, text, html
