[CmdletBinding()]
param(
    [System.Management.Automation.PSCredential] $Credential,
    [string] $RabbitHost = 'localhost',
    [int] $RabbitPort = 5672,
    [string] $ManagementUrl = 'http://127.0.0.1:15672'
)
$ErrorActionPreference = 'Stop'
$names = @('RABBITMQ_USERNAME','RABBITMQ_PASSWORD','RABBITMQ_HOST','RABBITMQ_PORT','RABBITMQ_ADDRESSES',
    'RABBITMQ_MANAGEMENT_URL','ADMIN_HTTP_USERNAME','ADMIN_HTTP_PASSWORD','RABBITMQ_ADMIN_INTEGRATION_TEST')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $PSScriptRoot
try {
    if (-not $Credential -and (-not $env:RABBITMQ_USERNAME -or -not $env:RABBITMQ_PASSWORD)) {
        $Credential = Get-Credential -Message 'Administrador LOCAL de RabbitMQ para crear un vhost temporal'
    }
    if ($Credential) {
        $env:RABBITMQ_USERNAME = $Credential.UserName
        $env:RABBITMQ_PASSWORD = $Credential.GetNetworkCredential().Password
    }
    $env:RABBITMQ_HOST = $RabbitHost
    $env:RABBITMQ_PORT = "$RabbitPort"
    $env:RABBITMQ_ADDRESSES = ''
    $env:RABBITMQ_MANAGEMENT_URL = $ManagementUrl.TrimEnd('/')
    $env:ADMIN_HTTP_USERNAME = 'integration-admin'
    $env:ADMIN_HTTP_PASSWORD = [Guid]::NewGuid().ToString('N')
    $env:RABBITMQ_ADMIN_INTEGRATION_TEST = 'true'
    & .\mvnw.cmd '-Dtest=RabbitAdminBrokerIT' test
    if ($LASTEXITCODE -ne 0) { throw 'Fallo la integracion real de RabbitMQ Admin. Revisar target/surefire-reports.' }
    Write-Host 'Integracion real aprobada en vhost temporal aislado, eliminado al terminar. No se consumieron colas operativas.'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
