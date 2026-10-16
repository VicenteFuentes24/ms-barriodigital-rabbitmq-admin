# RabbitMQ Admin en AWS

Proyecto Maven/Docker: `ms-barriodigital-rabbitmq-admin/` (carpeta interna).
Java 21 / Spring Boot 4.1.1, sin cambios de endpoints, seguridad o validaciones.
Ya estaba preparado para `RABBITMQ_ADDRESSES`; no requiere cambios Java.

Variables de broker: `RABBITMQ_ADDRESSES=10.0.1.62:5672,10.0.2.151:5672`,
`RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `RABBITMQ_VIRTUAL_HOST=barriodigital`.
Host/puerto locales siguen disponibles cuando addresses esta vacio.
En la configuracion AWS actual `RABBITMQ_SSL_ENABLED=false`: AMQP privado 5672,
no TLS 5671. No desactivar verificacion TLS de un endpoint que realmente use TLS.

`ADMIN_HTTP_USERNAME` y `ADMIN_HTTP_PASSWORD` son una cuenta HTTP INTERNA separada
del broker, Entra y Supabase. Password: minimo 16 caracteres, maximo 72 bytes UTF-8.
Las mutaciones siguen requiriendo `X-Admin-Request: true`, ademas de HTTP Basic.
Se mantienen los recursos reservados cmd.*, q.cmd.*, amq.*, borrados seguros y
validacion de payload. No colocar estas credenciales en variables VITE_*.

Usar el Compose AWS central de infra, sin publicar 8088. La imagen escucha en
`SERVER_ADDRESS=0.0.0.0` DENTRO del contenedor; su healthcheck ya maneja Basic
sin exponer la cuenta en argumentos del proceso. Se conservan non-root, filesystem
read-only, tmpfs y cap_drop. La red bridge necesita salida hacia las EC2 RabbitMQ;
interna significa sin puerto publico, no bloquear todo el trafico saliente.

No se agrega proxy BFF: no hay contrato administrativo obligatorio del frontend
ni ruta EP4 definida para ese acceso. Para la demostracion se usa acceso privado
por SSH a la IP Docker obtenida en ese momento y Basic + X-Admin-Request, segun
la guia de infra. No se inventan interfaces ni un sistema nuevo de autenticacion.
El `compose.internal.yml` existente sigue intacto, incluido su default TLS;
la configuracion AWS central especifica el protocolo del broker existente.

```powershell
cd ms-barriodigital-rabbitmq-admin
.\mvnw.cmd -B -ntp clean package
```

Pruebas reales opt-in siguen disponibles en el README existente, en vhost aislado.
No fueron ejecutadas contra AWS. Guia general: README.AWS.md de infra-barriodigital.
