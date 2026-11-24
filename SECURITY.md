# Política de Seguridad

La privacidad y la integridad criptográfica son pilares centrales de **Relay**. Si descubres una vulnerabilidad de seguridad en el cliente Android/Desktop, en la aplicación Web (`chat.josepe.dev`), en el protocolo Mesh o en la infraestructura de respaldo, agradecemos que la reportes de forma responsable antes de divulgarla públicamente.

## Qué reportar

Nos interesa especialmente conocer:
- Debilidades en el intercambio de claves (**ECDH P-256**), cifrado (**AES-256-GCM**), firmas (**Ed25519**) o formato de respaldo **`.JCB`**.
- Fallos de aislamiento multi-cuenta o fuga de material criptográfico en almacenamiento local.
- Vulnerabilidades de autenticación, autorización o exposición indebida de datos en tránsito o en caché.
- Denegación de servicio (*broadcast storm*, agotamiento de memoria/batería) en el protocolo Mesh offline.
- Errores de validación de entrada o ejecución insegura en Desktop/Android.

## Cómo reportar

1. **No abras un Issue público** con detalles explotables si el fallo afecta a otros usuarios o permite acceso no autorizado.
2. Usa la pestaña **Security → Advisories → Report a vulnerability** de este repositorio en GitHub (si está habilitada), o abre una solicitud de contacto privada a través de [josepe.dev](https://www.josepe.dev) indicando `[SECURITY] Relay`.
3. Incluye en la medida de lo posible:
   - Versión afectada (ej. `v2.9.0`, plataforma Android / Desktop / Web).
   - Pasos claros para reproducir el problema o prueba de concepto mínima.
   - Impacto estimado.

## Alcance fuera de límite

- Pruebas automatizadas de carga masiva (DDoS) contra `chat.josepe.dev` o servicios de terceros (Supabase, Firebase, Agora, Cloudflare).
- Ingeniería social o phishing sobre usuarios finales.
