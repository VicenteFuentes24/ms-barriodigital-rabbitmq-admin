# ms-barriodigital-rabbitmq-admin

Administrador REST real de RabbitMQ para BarrioDigital EP3/EP4. Java 21,
Spring Boot 4.1.1, Maven. Se uso el proyecto Initializr existente, no otro proyecto.
El scaffold tenia el package `cl.barriodigital.ms_barriodigital_rabbitmq_admin`;
se movieron su aplicacion y test a `cl.barriodigital.rabbitmqadmin`, como exige el TXT.
Se conservaron groupId, artifactId, wrapper y version Spring Boot.

## Arquitectura y alcance

```text
Postman local -> HTTP Basic / rol Admin -> Controller -> RabbitAdminService
                                                   -> ResourcePolicy
                                                   -> RabbitAdmin / RabbitTemplate -> AMQP
Futuro: React -> BFF (JWT Entra, scope y rol Admin) -> red interna -> este servicio
```

No hay base de datos, datos mock de produccion, Kafka, productores ni consumidores
de mensajes. No se importan definiciones ni se modifican politicas DLQ.
RabbitAdmin tiene autoStartup=false y redeclareManualDeclarations=false;
spring.rabbitmq.dynamic=false. Las operaciones solo ocurren tras una peticion REST.

`infra-barriodigital/rabbitmq/definitions.json` sigue siendo responsable exclusivo
de la topologia de Requests/Notify. Se inspeccionaron sus configuraciones sin editarlas.
Este servicio NO tiene dependencias Java de esos microservicios.

## Seguridad local

TODOS los endpoints, incluido /actuator/health, exigen HTTP Basic con una cuenta
administrativa externa definida en variables. No existe usuario/password por defecto.
Spring Security verifica la clave con BCrypt y asigna internamente ROLE_Admin a
esa unica cuenta de servicio; no almacena tokens ni implementa login de ciudadanos.
No acepta X-User-Roles ni claims enviados por el cliente como autoridad.

Para POST/DELETE de API enviar ademas `X-Admin-Request: true`. Es un marcador no
secreto para clientes administrativos, no un reemplazo de la autenticacion. CSRF
no se desactiva globalmente: solo se exceptuan peticiones API que incluyen ese
header no simple. Sin el header se aplica CSRF normal y se obtiene 403 si falta
un token valido. No hay CORS habilitado; no ampliar CORS ni quitar el marcador
sin revisar el riesgo CSRF de HTTP Basic. No usarlo directamente desde React.

Java escucha por defecto en 127.0.0.1:8088. Compose publica solo 127.0.0.1:8088.
Basic sobre HTTP se admite exclusivamente en desarrollo local/red protegida.
Para trafico entre hosts usar TLS o un canal interno cifrado, secretos externos,
cuenta AMQP de minimo privilegio y restricciones de red. No exponerlo a Internet.
Solo health de Actuator esta expuesto, tambien autenticado, sin detalles privados.

## Configuracion

Spring NO carga `.env` automaticamente. Exportar variables o usar Compose
`--env-file .env.local`. Copiar `.env.example` a un archivo local ignorado si se
prefiere; nunca registrar passwords reales. No reutilizar claves Azure/Supabase.

| Variable | Default / significado |
| --- | --- |
| SERVER_PORT | 8088 |
| SERVER_ADDRESS | 127.0.0.1 para Java; 0.0.0.0 dentro del contenedor |
| RABBITMQ_HOST / PORT | localhost / 5672; Compose usa rabbitmq:5672 |
| RABBITMQ_ADDRESSES | Vacio local; futuro host1:puerto,host2:puerto; prevalece sobre host/port |
| RABBITMQ_USERNAME / PASSWORD | Obligatorios, cuenta AMQP del vhost |
| RABBITMQ_VIRTUAL_HOST | barriodigital |
| RABBITMQ_SSL_ENABLED | false local; habilitar para listeners TLS configurados |
| ADMIN_HTTP_USERNAME | Obligatorio; ASCII, 1..64 caracteres, sin espacios ni ':' |
| ADMIN_HTTP_PASSWORD | Obligatorio; minimo 16 caracteres, maximo 72 bytes UTF-8 |
| BARRIODIGITAL_NETWORK_NAME | infra-barriodigital_barriodigital-net |
| RABBITMQ_ADMIN_INTEGRATION_TEST | true solo para la prueba opt-in |
| RABBITMQ_MANAGEMENT_URL | Solo tests: http://127.0.0.1:15672 |

AMQP tiene timeout de conexion/RPC de 5 segundos y heartbeat de 30 segundos.
No se reintentan automaticamente operaciones administrativas inciertas.
Las credenciales HTTP y AMQP son distintas. No se requiere Management API para
operaciones de produccion: se usan RabbitAdmin y callbacks AMQP apropiados.
Management solo se utiliza para aislar/limpiar las pruebas y para observacion manual.

## Iniciar con Java

Requisitos: JDK 21, RabbitMQ de Infra healthy con la topologia ya importada.
No iniciar otro broker ni cambiar el Compose anterior.

```powershell
cd 'G:\Desarrollo EP3-EP4\ms-barriodigital-rabbitmq-admin\ms-barriodigital-rabbitmq-admin'
$broker = Get-Credential -Message 'Cuenta AMQP local de RabbitMQ'
$env:RABBITMQ_USERNAME = $broker.UserName
$env:RABBITMQ_PASSWORD = $broker.GetNetworkCredential().Password
$env:RABBITMQ_HOST = 'localhost'
$env:RABBITMQ_PORT = '5672'
$env:RABBITMQ_VIRTUAL_HOST = 'barriodigital'
$admin = Get-Credential -Message 'Cuenta HTTP propia: elegir clave nueva de 16 a 72 caracteres ASCII'
$env:ADMIN_HTTP_USERNAME = $admin.UserName
$env:ADMIN_HTTP_PASSWORD = $admin.GetNetworkCredential().Password
.\mvnw.cmd spring-boot:run
```

Servicio: http://localhost:8088. Al detenerlo se pierden las conexiones de colas
exclusive propias, no los recursos durables del broker. No dejar credenciales
en transcripciones, logs compartidos, historial ni archivos versionados.

## Endpoints y validaciones

Todas las URLs siguientes son relativas a http://localhost:8088 y requieren Basic.

| Metodo | Ruta | Resultado |
| --- | --- | --- |
| POST | /api/rabbitmq/queues | 201 nueva / 200 declaracion equivalente existente |
| DELETE | /api/rabbitmq/queues/{name} | 204, solo vacia y sin consumidores |
| GET | /api/rabbitmq/queues/{name} | 200: name, messageCount, consumerCount reales |
| POST | /api/rabbitmq/exchanges | 201 nuevo / 200 declaracion equivalente existente |
| DELETE | /api/rabbitmq/exchanges/{name} | 204, solo sin bindings |
| POST | /api/rabbitmq/bindings | 201 declaracion confirmada, tambien idempotente |
| DELETE | /api/rabbitmq/bindings | 204; body JSON igual al de creacion |
| GET | /actuator/health | 200 UP / 503 DOWN, autenticado |

GET de cola usa queueDeclarePassive: no crea colas y obtiene contadores del broker.
NO se implemento un listado ficticio de recursos: AMQP no proporciona ese listado.
Tampoco se inventan durable/type/arguments al consultar una cola pasivamente.

Reglas de entrada:

- Names obligatorios ASCII 1..200: letra/digito inicial, despues letras, digitos,
  punto, guion, underscore o ':'. Sin slash, espacios, controles ni nombres vacios.
  Es un subconjunto deliberado y seguro del limite de 255 bytes del broker.
- Queue defaults: durable=true, exclusive=false, autoDelete=false. Omitidos/null
  usan defaults; si estan presentes como valores, los booleanos deben ser JSON boolean.
- exclusive=true requiere durable=false y autoDelete=true. La cola pertenece a
  la conexion AMQP de Admin, no a la conexion del cliente HTTP; no es recomendable
  para integracion duradera entre servicios. No se recrea al perder esa conexion.
- Exchange type exactamente direct, topic o fanout; durable=true, autoDelete=false.
- routingKey debe estar presente, ASCII hasta 255 caracteres [A-Za-z0-9._*#:-].
  La cadena vacia es valida (por ejemplo fanout); ausente/null no lo es.
- Se rechazan campos desconocidos, JSON duplicado/malformado, coerciones de strings
  a booleanos y documentos excesivos. No se aceptan argumentos arbitrarios ni DLX.
- Bind/unbind requieren existencia de exchange y cola. Eliminar un binding ya
  ausente devuelve 204 si ambos recursos existen: AMQP unbind es idempotente.

## Proteccion de datos y topologia

Se bloquea toda mutacion de nombres `cmd.*`, `q.cmd.*` y `amq.*`, sin distincion
de mayusculas, en TODOS los vhosts. Incluye los nueve recursos obligatorios:

```text
cmd.direct, cmd.topic, cmd.dead.dlx
q.cmd.email, q.cmd.crew, q.cmd.certificate
q.cmd.email.dlq, q.cmd.crew.dlq, q.cmd.certificate.dlq
```

No se permite crearlos/redeclararlos, borrarlos ni agregar/quitar bindings que
los involucren. Tampoco se puede alterar el exchange default (nombre vacio).
La proteccion ocurre en service antes de contactar con el broker y no tiene bypass.
GET de una cola reservada es solo lectura y depende de permisos AMQP.

Queue.delete utiliza ifUnused=true e ifEmpty=true; Exchange.delete utiliza
ifUnused=true. El broker aplica esas condiciones incluso si cambia el estado
entre inspeccion y borrado. No hay purge, force delete, modificacion de policies,
usuarios ni vhosts mediante la API publica del servicio.

## Postman

Importar `examples/BarrioDigital-RabbitMQ-Admin.postman_collection.json`. Asignar
adminUsername/adminPassword como valores LOCALES secretos, no exportarlos ni
sincronizarlos. La coleccion hereda Basic y agrega el marcador administrativo.
Ejecutar en orden: health, cola, exchange, binding, inspeccion, unbind, borrar
exchange, borrar cola. Solo afecta ep3.demo.*, nunca q.cmd.* ni cmd.*.

Alternativa manual: Authorization -> Basic Auth con la cuenta HTTP propia.
Para POST y DELETE agregar X-Admin-Request: true. Bodies raw JSON y header
Content-Type: application/json. Para DELETE binding tambien enviar body JSON.

POST /api/rabbitmq/queues:

```json
{"name":"ep3.demo.queue","durable":true,"exclusive":false,"autoDelete":false}
```

POST /api/rabbitmq/exchanges:

```json
{"name":"ep3.demo.direct","type":"direct","durable":true,"autoDelete":false}
```

POST o DELETE /api/rabbitmq/bindings:

```json
{"exchange":"ep3.demo.direct","queue":"ep3.demo.queue","routingKey":"demo.created"}
```

Al finalizar: DELETE /api/rabbitmq/exchanges/ep3.demo.direct y despues
DELETE /api/rabbitmq/queues/ep3.demo.queue. Si devuelve 409, revisar bindings,
mensajes/consumidores antes de actuar; nunca purgar ni borrar volumen para probar.
Tambien hay ejemplos JSON de topic (routingKey demo.*) y fanout (routingKey "").

## Errores

ApiErrorResponse: timestamp UTC, status, error, message y path. Sin stack traces,
payload de credenciales ni mensajes crudos del broker. Logs incluyen operacion,
resultado y replyCode, no secretos ni headers Authorization.

| HTTP | Significado |
| --- | --- |
| 400 | DTO, path, JSON o configuracion no permitida invalidos |
| 401 | Credenciales HTTP ausentes/incorrectas |
| 403 | Falta rol Admin/CSRF, o el usuario AMQP no tiene permisos suficientes |
| 404 | Falta cola/exchange requerido, o endpoint inexistente |
| 409 | Nombre reservado, configuracion incompatible, recurso en uso/no vacio |
| 500 | Error inesperado propio, mensaje sanitizado |
| 503 | Broker inaccesible/autenticacion AMQP fallida/operacion no confirmada |

Las declaraciones son idempotentes si coinciden los atributos. El indicador
201/200 se basa en inspeccion previa; otros administradores concurrentes pueden
crear el recurso entre esa inspeccion y la declaracion. AMQP no devuelve un flag
atomico de "recien creado". Un timeout puede ocurrir despues de aplicar el cambio:
consultar el broker antes de repetir operaciones; no se promete transaccion REST.

## Tests automatizados

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean package
```

La suite normal no usa broker externo; mocks solo en TESTS. Verifica permisos,
DTO, service separado, nombres reservados, flags de borrado seguro, bindings,
contadores y errores. El test inicial de contexto se conservo y adapto al package.

Opt-in con el RabbitMQ real de Infra, sin modificar el vhost operativo:

```powershell
$brokerAdmin = Get-Credential -Message 'Administrador local de RabbitMQ Management'
.\Test-RabbitMQAdmin.ps1 -Credential $brokerAdmin
```

El script restaura variables y genera credenciales HTTP efimeras, nunca las imprime.
RabbitAdminBrokerIT crea un vhost bd-admin-it-<uuid>, concede permisos solo alli
y ejecuta peticiones HTTP reales + verificaciones AMQP. No importa definitions,
no consume/publica en barriodigital y limpia exclusivamente el vhost temporal.
Management requiere una cuenta local con permisos para crearlo; NO usar la cuenta
de servicio restringida para administrar vhosts. Si se mata la JVM abruptamente,
revisar vhosts bd-admin-it-* manualmente antes de retirar solo los del test propio.

La prueba real comprueba direct/topic/fanout, enrutamiento de mensajes, unbind,
declaraciones repetidas, conflictos, proteccion de topologia, ausencia de creacion
automatica, borrado seguro, health autenticado y 503 usando un puerto AMQP cerrado
en una segunda instancia temporal (no detiene el broker existente).

## Verificacion ejecutada (2026-10-15)

| Comprobacion | Resultado real |
| --- | --- |
| mvnw.cmd test | 55 tests normales aprobados |
| mvnw.cmd -B -ntp clean package | BUILD SUCCESS, 55 tests sin fallos/errores |
| Test-RabbitMQAdmin.ps1 tras el build limpio | 11 tests reales aprobados, 0 omitidos |
| Total de reportes Surefire | 66 tests, 0 fallos, 0 errores, 0 omitidos |
| docker build -t barriodigital-rabbitmq-admin:local . | Imagen construida correctamente |
| compose.local.yml y compose.internal.yml config --quiet | Ambos validos, sin desplegar AWS |
| Compose real con vhost temporal | healthy, health UP, uid=999, read-only=true, bind 127.0.0.1 |
| Operaciones REST desde el contenedor | Ocho checks correctos, incluida proteccion y borrado seguro |
| Health sin autenticacion | 401 JSON |
| Seis archivos JSON/coleccion Postman | Sintaxis valida |
| Hash de las 59 fuentes revisadas de Infra/Requests/Notify | Sin cambios |
| Limpieza posterior | Cero vhosts/contenedores temporales de Admin restantes |

Los reportes estan en target/surefire-reports. No quedo este servicio ejecutandose
contra el vhost operativo: iniciarlo con los comandos documentados. El broker y
Mailpit existentes no fueron eliminados ni reiniciados por estas pruebas.
El warning CDS de la JVM durante Mockito es de tests; no quedaron advertencias
unchecked en la compilacion limpia final. Los tests negativos provocan a proposito
logs 404/406 del broker y un error propio simulado para comprobar respuestas seguras.

No se probaron cluster de dos nodos, TLS entre hosts, integracion BFF/Entra ni AWS.
La coleccion Postman se valido como JSON; las operaciones se ejecutaron por los
tests HTTP y el smoke Docker, no mediante la interfaz grafica de Postman.

## Docker y futuro AWS

Con las cuatro variables de credenciales exportadas:

```powershell
docker compose --env-file .env.example -f compose.local.yml config --quiet
docker compose --env-file .env.example -f compose.local.yml up -d --build --wait --wait-timeout 180
docker compose --env-file .env.example -f compose.local.yml ps
docker compose --env-file .env.example -f compose.local.yml logs --tail 50
```

Usar solo este archivo, no mezclarlo con el Compose principal. La red externa y
RabbitMQ deben existir. Imagen multistage Java 21 fijada por digest, usuario no
root, filesystem read-only, tmpfs /tmp, sin capabilities. Docker HEALTHCHECK
usa Actuator autenticado mediante un cliente Java pequeno que lee secretos del
entorno: no los coloca en argumentos ni salida del healthcheck. Puerto configurable.

`compose.internal.yml` es solo una plantilla futura, SIN ports publicados.
Requiere RABBITMQ_ADMIN_IMAGE con version/digest, RABBITMQ_ADDRESSES con los dos
DNS internos y puerto real de cada nodo, credenciales externas y red privada.
No crea nodos RabbitMQ ni convierte automaticamente el broker actual en cluster.

Para AWS, pendiente de trabajo autorizado posterior:

1. Formar/validar el cluster y su almacenamiento, TLS, backups y permisos.
2. Mantener 8088 accesible solo desde BFF/red administrativa; AMQP solo desde
   servicios autorizados y Management solo desde canal administrativo privado.
3. BFF debe validar JWT/scope y exigir Admin antes de llamar a esta API con su
   identidad interna y X-Admin-Request. Mantener el secreto HTTP solo en servidor.
   No se modifico BFF y esta integracion NO esta implementada en esta fase.
4. Gestionar secretos/certificados fuera de Git y usar canal cifrado entre hosts.
5. Validar failover. Una lista de addresses permite reconexion, no garantiza HA.
   Las colas classic actuales no se vuelven replicadas al agregar otro nodo.
   Quorum requiere mayoria: dos replicas no toleran perder una. No se migraron
   tipos de cola ni se prometio alta disponibilidad de datos en este paso.

## RabbitMQ Management

Abrir http://localhost:15672 con las credenciales LOCALES del broker (no las HTTP
de Admin). Seleccionar el vhost correcto. En pruebas automaticas observar el
vhost temporal mientras corre; al terminar se elimina. En pruebas manuales ver
Queues/Exchanges y bindings de ep3.demo.*; confirmar routingKey y borrado posterior.
No tocar los nueve recursos reservados ni politicas DLQ. No abrir 15672 a Internet.

## Archivos creados/modificados

```text
pom.xml                                      modificado: Security y security-test
.gitignore                                   modificado: secretos/logs
src/main/resources/application.properties     modificado: entorno, 8088, AMQP
src/main/java/cl/barriodigital/rabbitmqadmin/
  MsBarriodigitalRabbitmqAdminApplication.java movido desde package Initializr
  config/{RabbitConfig,JsonConfig,SecurityConfig}.java
  controller/RabbitAdminController.java
  service/RabbitAdminService.java
  dto/{CreateQueueRequest,CreateExchangeRequest,BindingRequest,QueueInfoResponse}.java
  validation/ResourcePolicy.java
  exception/{ApiErrorResponse,AdminOperationException,GlobalExceptionHandler}.java
src/test/java/cl/barriodigital/rabbitmqadmin/
  MsBarriodigitalRabbitmqAdminApplicationTests.java movido/modificado
  controller/RabbitAdminControllerTest.java
  service/RabbitAdminServiceTest.java
  RabbitAdminBrokerIT.java
docker/HealthCheck.java
Dockerfile, .dockerignore, .env.example
compose.local.yml, compose.internal.yml
Test-RabbitMQAdmin.ps1, README.md
examples/
  queue.json, exchange-direct.json, exchange-topic.json, exchange-fanout.json
  binding.json, BarrioDigital-RabbitMQ-Admin.postman_collection.json
```

No se modificaron frontend, BFF, Requests, Catalog, Notify ni Infra. Sin Supabase,
Azure, git push ni despliegue AWS. El directorio recibido no tenia repositorio Git;
no se inicializo uno. No se almacenaron credenciales reales.

Referencias: [RabbitAdmin](https://docs.spring.io/spring-amqp/docs/current/api/org/springframework/amqp/rabbit/core/RabbitAdmin.html),
[AMQP y borrado condicional](https://www.rabbitmq.com/docs/specification),
[CSRF y Basic](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html),
[quorum y mayorias](https://www.rabbitmq.com/docs/quorum-queues).
