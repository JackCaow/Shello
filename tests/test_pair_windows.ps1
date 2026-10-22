# No UAC, package, service or networking mutations: all machine operations below are mocked.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$launcher=Join-Path $root 'companion/pair.ps1'
$parseErrors=$null;$tokens=$null
[void][Management.Automation.Language.Parser]::ParseFile($launcher,[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw ($parseErrors|Out-String)}
. $launcher
$count=0
function Assert($ok,[string]$message){if(-not $ok){throw $message};$script:count++}
function Reject([scriptblock]$action,[string]$pattern){$caught=$false;try{&$action}catch{$caught=$_.Exception.Message -match $pattern};Assert $caught "Expected rejection: $pattern"}
Assert ((Get-FileHash $launcher).Hash -eq (Get-FileHash (Join-Path $root 'skills/shello-pair/scripts/pair.ps1')).Hash) 'Copied skill must carry the identical standalone launcher'
foreach($entry in @{'pair.sh'='companion/pair.sh';'wsl-host.sh'='companion/windows/wsl-host.sh';'start_pairing.py'='skills/shello-pair/scripts/start_pairing.py';'render_qr.py'='skills/shello-pair/scripts/render_qr.py'}.GetEnumerator()){
 $embedded=[Convert]::FromBase64String($script:ShelloBundle[$entry.Key])
 Assert ([Convert]::ToBase64String($embedded) -eq [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $root $entry.Value)))) "Embedded $($entry.Key) drift"
}
foreach($ip in @('10.2.3.4','172.16.0.1','172.31.255.254','192.168.15.191','100.64.0.1','100.127.255.254')){Assert (Test-PairAddress $ip) "Accept $ip"}
foreach($ip in @('127.0.0.1','0.0.0.0','8.8.8.8','100.63.1.1','100.128.0.1','172.32.0.1','192.168.999.1','::1')){Assert (-not(Test-PairAddress $ip)) "Reject $ip"}
Assert ((Get-NetworkCidr '192.168.15.191' 24) -eq '192.168.15.0/24') 'Normalize LAN firewall subnet'
Assert ((Get-NetworkCidr '172.22.80.20' 20) -eq '172.22.80.0/20') 'Non-octet CIDR'
Assert ((Get-NetworkCidr '10.2.3.4' 32) -eq '10.2.3.4/32') 'Single host CIDR'
Reject {Get-NetworkCidr '10.0.0.1' 0} 'Invalid'
Assert-Wsl2Distribution "  NAME STATE VERSION`n* Ubuntu Running 2`n  Debian Stopped 1" 'Ubuntu';$count++
Assert-Wsl2Distribution "* Ubuntu 正在运行 2" 'Ubuntu';$count++
Reject {Assert-Wsl2Distribution '* Ubuntu Stopped 1' 'Ubuntu'} 'WSL2'
Reject {Assert-Wsl2Distribution '* Ubuntu-Other Stopped 2' 'Ubuntu'} 'WSL2'
function Get-NetIPAddress {param($AddressFamily) @(
 [pscustomobject]@{IPAddress='172.20.0.1';InterfaceAlias='vEthernet (WSL)';PrefixLength=20;InterfaceIndex=1},
 [pscustomobject]@{IPAddress='192.168.15.191';InterfaceAlias='Wi-Fi';PrefixLength=24;InterfaceIndex=2},
 [pscustomobject]@{IPAddress='100.90.1.2';InterfaceAlias='Tailscale';PrefixLength=32;InterfaceIndex=3})}
Assert ((Get-WindowsEndpoint 'auto' '').Address -eq '100.90.1.2') 'Auto prefers Windows Tailscale'
Assert ((Get-WindowsEndpoint 'lan' '').Remote -eq '192.168.15.0/24') 'LAN skips virtual interface'
Reject {Get-WindowsEndpoint 'lan' '100.90.1.2'} 'does not match'
Reject {Get-WindowsEndpoint 'auto' '192.168.15.111'} 'assigned'
# Round trip actual native argv including spaces, quotes, trailing backslashes and Unicode.
$child=Join-Path ([IO.Path]::GetTempPath()) ('shello-argv-'+[Guid]::NewGuid()+'.py')
try{
 [IO.File]::WriteAllText($child,'import json,sys; print(json.dumps(sys.argv[1:]))')
 $values=@('plain','two words','','a"b','C:\folder with space\','literal $() ; &','中文')
 # powershell.exe -File discards empty arguments before the script receives them.
 # Use an ordinary native argv consumer to test the quoting used for wsl.exe.
 $python=if($env:OS -eq 'Windows_NT'){'python'}else{'python3'}
 $result=Invoke-ShelloCommand (Get-Command $python).Source (@($child)+$values)
 Assert ($result.Code -eq 0) 'Native child exit'
 $actual=@($result.Text|ConvertFrom-Json)
 Assert ($actual.Count -eq $values.Count) 'Native argv count'
 for($i=0;$i -lt $values.Count;$i++){Assert ($actual[$i] -ceq $values[$i]) "Native argv round trip $i"}
}finally{Remove-Item -LiteralPath $child -Force -ErrorAction SilentlyContinue}
$native=${function:Invoke-ShelloCommand}
function Invoke-ShelloCommand {param($File,$Arguments) [pscustomobject]@{Code=0;Error='';Text="监听 ipv4: 连接 ipv4:`n192.168.15.191 2222 172.20.0.2 2222"}}
$existing=Get-PortProxy '192.168.15.191' 2222
Assert ($existing.Target -eq '172.20.0.2') 'Localized netsh table'
Assert ($null -eq (Get-PortProxy '192.168.15.191' 9999)) 'Unmatched proxy'
Assert-ProxyOwnership $existing $existing $null;$count++
Reject {Assert-ProxyOwnership $existing $null $null} 'not be overwritten'
$changed=[pscustomobject]@{Address=$existing.Address;Port=2222;Target='172.20.0.3';TargetPort=2222}
Reject {Assert-ProxyOwnership $changed $existing $null} 'not be overwritten'
# Simulate owned NAT lease and cleanup. A changed foreign rule must survive cleanup.
$temp=Join-Path ([IO.Path]::GetTempPath()) ('shello-net-test-'+[Guid]::NewGuid())
[void][IO.Directory]::CreateDirectory($temp)
$script:proxies=@{};$script:firewalls=@{};$script:hv=@{};$script:mutations=New-Object 'Collections.Generic.List[string]'
function Get-PortProxy {param($Address,$Port) return $script:proxies[[string]$Port]}
function Set-ShelloProxy {param($Endpoint) $script:proxies[[string]$Endpoint.Port]=$Endpoint;$script:mutations.Add('proxy '+$Endpoint.Port)}
function Invoke-ShelloCommand {param($File,$Arguments)
 $port=($Arguments|Where-Object{$_ -like 'listenport=*'}) -replace 'listenport=',''
 [void]$script:proxies.Remove([string]$port);$script:mutations.Add('delete '+$port)
 return [pscustomobject]@{Code=0;Text='';Error=''}
}
function Get-NetTCPConnection {param($State,$LocalPort,$ErrorAction) if($script:busyPort -eq $LocalPort){[pscustomobject]@{LocalAddress='0.0.0.0'}}}
function Get-Service {param($Name) [pscustomobject]@{Status='Running'}}
function Start-Service {throw 'Do not change services in this test'}
function Get-NetFirewallRule {param($Name,$ErrorAction) $script:firewalls[$Name]}
function Remove-NetFirewallRule {param($Name) [void]$script:firewalls.Remove($Name);$script:mutations.Add('remove '+$Name)}
function New-NetFirewallRule {param($Name,$DisplayName,$Group,$Direction,$Action,$Protocol,$LocalPort,$LocalAddress,$RemoteAddress,$Profile)
 Assert ($RemoteAddress -eq '192.168.15.0/24') 'Scoped Windows firewall'
 $script:firewalls[$Name]=[pscustomobject]@{Group=$Group};$script:mutations.Add('firewall '+$Name)
}
function Get-NetFirewallHyperVRule {param($Name,$ErrorAction) $script:hv[$Name]}
function Remove-NetFirewallHyperVRule {param($Name) [void]$script:hv.Remove($Name)}
function New-NetFirewallHyperVRule {param($Name,$DisplayName,$Direction,$VMCreatorId,$Protocol,$LocalPorts,$RemoteAddresses)
 Assert ($RemoteAddresses -in @('172.20.0.1','192.168.15.0/24')) 'Scoped Hyper-V firewall'
 $script:hv[$Name]=[pscustomobject]@{DisplayName=$DisplayName}
}
$script:busyPort=0
$plan=[pscustomobject]@{Address='192.168.15.191';Remote='192.168.15.0/24';Direct=$false;Gateway='172.20.0.1';Group='test-owner';
 Ssh=[pscustomobject]@{Address='192.168.15.191';Port=2222;Target='172.20.0.2';TargetPort=2222};
 Pair=[pscustomobject]@{Address='192.168.15.191';Port=8765;Target='172.20.0.2';TargetPort=8765};
 SshRule='test-ssh';PairRule='test-pair';State=(Join-Path $temp 'state.json');Ack=(Join-Path $temp 'ack.json');Stop=(Join-Path $temp 'stop');LeaseSeconds=1}
try{
 [IO.File]::WriteAllText($plan.Stop,'stop')
 Invoke-NetworkLease $plan
 Assert ($script:proxies.ContainsKey('2222') -and -not $script:proxies.ContainsKey('8765')) 'Cleanup keeps SSH only'
 Assert ($script:firewalls.ContainsKey('test-ssh') -and -not $script:firewalls.ContainsKey('test-pair')) 'Cleanup firewall keeps SSH only'
 Assert (($script:hv.Keys -join ',') -eq 'test-ssh-HV') 'Hyper-V cleanup keeps SSH only'
 Assert ((Get-Content -Raw $plan.Ack|ConvertFrom-Json).ready) 'Lease readiness acknowledgement'
 # Reuse owned SSH mapping but refuse a same-looking unowned one.
 Invoke-NetworkLease $plan
 Remove-Item $plan.State
 $before=$script:mutations.Count
 Reject {Invoke-NetworkLease $plan} 'not be overwritten'
 Assert ($script:mutations.Count -eq $before) 'Refusal is mutation-free'
 Write-PrivateJson $plan.State $plan.Ssh
 $script:proxies['8765']=$plan.Pair
 Reject {Invoke-NetworkLease $plan} 'already forwarded'
 $script:proxies.Remove('8765');$script:busyPort=8765
 Reject {Invoke-NetworkLease $plan} 'listener occupies'
 $script:busyPort=0
 # Teardown must not delete a mapping or firewall replaced by another owner.
 $script:proxies['8765']=[pscustomobject]@{Target='172.20.0.99';TargetPort=8765}
 $script:firewalls['test-pair']=[pscustomobject]@{Group='foreign'}
 $script:hv['test-pair-HV']=[pscustomobject]@{DisplayName='foreign'}
 Remove-PairNetwork $plan
 Assert ($script:proxies.ContainsKey('8765') -and $script:firewalls.ContainsKey('test-pair') -and $script:hv.ContainsKey('test-pair-HV')) 'Preserve foreign replacements'
 Reject {Set-ShelloFirewall 'test-pair' $plan 8765} 'unrelated firewall'
 $script:proxies.Remove('8765');$script:firewalls.Remove('test-pair');$script:hv.Remove('test-pair-HV')
 $plan.Direct=$true
 Invoke-NetworkLease $plan
 Assert (-not $script:proxies.ContainsKey('2222')) 'Mirrored network removes only old owned NAT forwarding'
 Assert (-not(Test-Path $plan.State)) 'Remove stale NAT ownership'
 # Expired lease must finish without a stop marker.
 $plan.Direct=$false;Remove-Item $plan.Stop
 $watch=[Diagnostics.Stopwatch]::StartNew();Invoke-NetworkLease $plan
 Assert ($watch.Elapsed.TotalSeconds -lt 5 -and -not $script:proxies.ContainsKey('8765')) 'Lease expires and cleans up'
}finally{Remove-Item -LiteralPath $temp -Recurse -Force}
# Prerequisite routing can be tested without installing WSL or changing the calling user's distro.
$oldOS=$env:OS;$oldLocal=$env:LOCALAPPDATA
$env:OS='Windows_NT';$env:LOCALAPPDATA=Join-Path ([IO.Path]::GetTempPath()) ('shello-route-'+[Guid]::NewGuid())
$script:statusCode=1;$script:distributionPresent=$true;$script:linuxUser='tester';$script:version=2;$script:elevations=0
function Protect-ShelloFolder {param($Path) [void][IO.Directory]::CreateDirectory($Path)}
function Get-Command {param($Name,$ErrorAction) if($Name -eq 'wsl.exe'){[pscustomobject]@{Name='wsl.exe'}}else{Microsoft.PowerShell.Core\Get-Command $Name -ErrorAction SilentlyContinue}}
function Invoke-ShelloCommand {param($File,$Arguments) if($Arguments[0] -ne '--status'){throw 'Unexpected native command'};[pscustomobject]@{Code=$script:statusCode;Text='WSL state';Error=''}}
function Invoke-Wsl {param($Arguments)
 switch($Arguments[0]){
  '--list' {if($Arguments[1] -eq '--quiet'){if($script:distributionPresent){return 'Ubuntu'}else{return 'Debian'}}else{return ('* Ubuntu Running '+$script:version)}}
  '-d' {return $script:linuxUser}
  default {throw 'Unexpected WSL mutation'}
 }
}
function Start-ElevatedAction {param($Action,$Plan)
 $script:elevations++;$spec=Get-Content -Raw $Plan|ConvertFrom-Json
 Write-PrivateJson $spec.Ack @{ready=$true;code=3010;message='restart required'}
 $child=New-Object psobject;$child|Add-Member ScriptMethod WaitForExit {};return $child
}
try{
 $Check=$true
 Assert ((Invoke-ShelloPairing) -eq 2) 'Missing WSL check is resumable'
 Assert ($script:elevations -eq 0) 'Check never elevates'
 $Check=$false
 Assert ((Invoke-ShelloPairing) -eq 77) 'WSL restart returns 77 without reboot'
 Assert ($script:elevations -eq 1) 'Only machine setup elevated'
 $script:statusCode=0;$script:distributionPresent=$false;$Check=$true
 Assert ((Invoke-ShelloPairing) -eq 2) 'Missing distribution check'
 $script:distributionPresent=$true;$script:version=1
 Reject {Invoke-ShelloPairing} 'WSL2'
 $script:version=2;$script:linuxUser='root'
 Assert ((Invoke-ShelloPairing) -eq 77) 'Uninitialized root-only distro requires ordinary user'
 $script:linuxUser='tester'
 Assert ((Invoke-ShelloPairing) -eq 0) 'Existing WSL2 and user accepted'
 Assert ($script:elevations -eq 1) 'Ready check does not elevate or install'
 Assert (@(Get-ChildItem -Path $env:LOCALAPPDATA -Filter '*.png' -Recurse).Count -eq 0) 'Check creates no QR material'
}finally{Remove-Item -LiteralPath $env:LOCALAPPDATA -Recurse -Force -ErrorAction SilentlyContinue;$env:OS=$oldOS;$env:LOCALAPPDATA=$oldLocal}
[Console]::Out.WriteLine("PASS: $count Windows pairing assertions; machine commands mocked.")
