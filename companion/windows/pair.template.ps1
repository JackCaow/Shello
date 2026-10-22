# Generated as companion/pair.ps1. Supports Windows PowerShell 5.1 and PowerShell 7.
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')][string]$Distribution = 'Ubuntu',
    [ValidateSet('auto','lan','tailscale')][string]$Network = 'auto',
    [string]$HostAddress,
    [ValidateRange(1024,65535)][int]$SshPort = 2222,
    [ValidateRange(1024,65535)][int]$PairPort = 8765,
    [ValidateRange(30,600)][int]$Ttl = 300,
    [switch]$Check,
    [switch]$Json,
    [string]$MachineAction,
    [string]$PlanFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:ShelloBundle = @{
    'pair.sh' = '__PAIR_SH__'
    'wsl-host.sh' = '__WSL_HOST__'
    'start_pairing.py' = '__START_PAIRING__'
    'render_qr.py' = '__RENDER_QR__'
}
function Write-ShelloEvent([string]$Event, [hashtable]$Fields = @{}) {
    $Fields.event = $Event
    [Console]::Out.WriteLine(($Fields | ConvertTo-Json -Compress -Depth 8))
}
function Write-PrivateJson([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding($false)))
}
function Quote-NativeArgument([string]$Value) {
    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') { return $Value }
    $Value = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $Value = [regex]::Replace($Value, '(\\+)$', '$1$1')
    return '"' + $Value + '"'
}
function Join-NativeArguments([string[]]$Values) { return (($Values | ForEach-Object { Quote-NativeArgument $_ }) -join ' ') }
function New-ShelloProcess([string]$File, [string[]]$Arguments) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $File; $info.Arguments = Join-NativeArguments $Arguments
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = New-Object Text.UTF8Encoding($false)
    $info.StandardErrorEncoding = New-Object Text.UTF8Encoding($false)
    $p = New-Object Diagnostics.Process; $p.StartInfo = $info
    [void]$p.Start(); return $p
}
function Invoke-ShelloCommand([string]$File, [string[]]$Arguments) {
    $p = New-ShelloProcess $File $Arguments
    $stdout = $p.StandardOutput.ReadToEndAsync(); $stderr = $p.StandardError.ReadToEndAsync()
    if (-not $p.WaitForExit(600000)) { $p.Kill(); throw "Command timed out: $File" }
    $text = $stdout.GetAwaiter().GetResult().Replace([string][char]0,'')
    $errorText = $stderr.GetAwaiter().GetResult()
    $code = $p.ExitCode; $p.Dispose()
    return [pscustomobject]@{ Code=$code; Text=$text.Trim(); Error=$errorText.Trim() }
}
function Invoke-Wsl([string[]]$Arguments) {
    $r = Invoke-ShelloCommand 'wsl.exe' $Arguments
    if ($r.Code -ne 0) { throw "WSL command failed ($($r.Code)): $($r.Error) $($r.Text)" }
    return $r.Text
}
function Test-ShelloAdministrator {
    return (New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}
function Protect-ShelloFolder([string]$Path) {
    [void][IO.Directory]::CreateDirectory($Path)
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true,$false)
    foreach ($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')) {
        $identity = New-Object Security.Principal.SecurityIdentifier($sid)
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}
function Get-ShelloHash([string]$Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-','').ToLowerInvariant().Substring(0,16) } finally { $sha.Dispose() }
}
function Test-PairAddress([string]$Address) {
    $ip = $null
    if (-not [Net.IPAddress]::TryParse($Address,[ref]$ip) -or $ip.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { return $false }
    $b = $ip.GetAddressBytes()
    return ($b[0] -eq 10 -or ($b[0] -eq 172 -and $b[1] -ge 16 -and $b[1] -le 31) -or ($b[0] -eq 192 -and $b[1] -eq 168) -or ($b[0] -eq 100 -and $b[1] -ge 64 -and $b[1] -le 127))
}
function Get-NetworkCidr([string]$Address,[int]$Prefix) {
    if ($Prefix -lt 1 -or $Prefix -gt 32) { throw 'Invalid interface prefix.' }
    $bytes=[Net.IPAddress]::Parse($Address).GetAddressBytes()
    for($i=0;$i -lt 4;$i++) { $bits=[Math]::Min(8,[Math]::Max(0,$Prefix-$i*8));$mask=if($bits -eq 0){0}else{(255 -shl (8-$bits)) -band 255};$bytes[$i]=$bytes[$i] -band $mask }
    return ($bytes -join '.')+'/'+$Prefix
}
function Assert-Wsl2Distribution([string]$Listing,[string]$Name) {
    if ($Listing -notmatch ('(?m)^\s*\*?\s*'+[regex]::Escape($Name)+'\s+.+\s+2\s*$')) { throw 'Select a WSL2 distribution. Existing WSL1 distributions are not converted automatically.' }
}
function Get-WindowsEndpoint([string]$Choice, [string]$Explicit) {
    $addresses = @(Get-NetIPAddress -AddressFamily IPv4 | Where-Object { Test-PairAddress $_.IPAddress })
    if ($Explicit) {
        $selected = @($addresses | Where-Object IPAddress -eq $Explicit)
        if ($selected.Count -ne 1) { throw '-HostAddress must be a LAN/Tailscale IPv4 assigned to this Windows computer.' }
    } else {
        $selected = @($addresses | Where-Object { $_.IPAddress -match '^100\.' -and (Test-PairAddress $_.IPAddress) })
        if ($Choice -eq 'lan') { $selected=@() }
        if ($selected.Count -eq 0 -and $Choice -ne 'tailscale') {
            $selected = @($addresses | Where-Object { $_.IPAddress -notmatch '^100\.' -and $_.InterfaceAlias -notmatch '(?i)vEthernet|WSL|Docker|Loopback' } | Sort-Object InterfaceIndex)
        }
        if ($selected.Count -eq 0) { throw 'No usable Windows address. Connect Wi-Fi/Tailscale or specify -HostAddress.' }
        $selected = @($selected[0])
    }
    $address = $selected[0].IPAddress
    $tail = $address -match '^100\.'
    if (($Choice -eq 'lan' -and $tail) -or ($Choice -eq 'tailscale' -and -not $tail)) { throw 'Address does not match -Network.' }
    $source = if ($tail) { '100.64.0.0/10' } else { Get-NetworkCidr $address $selected[0].PrefixLength }
    return [pscustomobject]@{ Address=$address; Remote=$source }
}
function Get-PortProxy([string]$Address,[int]$Port) {
    $r = Invoke-ShelloCommand 'netsh.exe' @('interface','portproxy','show','v4tov4')
    if ($r.Code -ne 0) { throw 'Cannot inspect existing port forwarding.' }
    foreach ($line in ($r.Text -split "`n")) {
        if ($line -match '^\s*(\d+\.\d+\.\d+\.\d+)\s+(\d+)\s+(\d+\.\d+\.\d+\.\d+)\s+(\d+)\s*$') {
            if ($Matches[1] -eq $Address -and [int]$Matches[2] -eq $Port) { return [pscustomobject]@{Address=$Matches[1];Port=[int]$Matches[2];Target=$Matches[3];TargetPort=[int]$Matches[4]} }
        }
    }
    return $null
}
function Assert-ProxyOwnership($Existing,$Previous,$Desired) {
    if ($null -eq $Existing) { return }
    foreach ($owned in @($Previous,$Desired)) {
        if ($null -ne $owned -and $Existing.Address -eq $owned.Address -and $Existing.Port -eq $owned.Port -and $Existing.Target -eq $owned.Target -and $Existing.TargetPort -eq $owned.TargetPort) { return }
    }
    throw 'A different port forwarding rule uses this endpoint. Choose another port; it will not be overwritten.'
}
function Set-ShelloProxy($Endpoint) {
    $r=Invoke-ShelloCommand 'netsh.exe' @('interface','portproxy','add','v4tov4',"listenaddress=$($Endpoint.Address)","listenport=$($Endpoint.Port)","connectaddress=$($Endpoint.Target)","connectport=$($Endpoint.TargetPort)")
    if ($r.Code -ne 0) { throw 'Cannot configure the owned WSL port forwarding rule.' }
}
function Set-ShelloFirewall([string]$Name,$Plan,[int]$Port) {
    $old=Get-NetFirewallRule -Name $Name -ErrorAction SilentlyContinue
    if ($old -and $old.Group -ne $Plan.Group) { throw 'An unrelated firewall rule has this name.' }
    if ($old) { Remove-NetFirewallRule -Name $Name }
    New-NetFirewallRule -Name $Name -DisplayName $Name -Group $Plan.Group -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port -LocalAddress $Plan.Address -RemoteAddress $Plan.Remote -Profile Any | Out-Null
    if (Get-Command New-NetFirewallHyperVRule -ErrorAction SilentlyContinue) {
        $hvName=$Name+'-HV'
        $hv=Get-NetFirewallHyperVRule -Name $hvName -ErrorAction SilentlyContinue
        if ($hv -and $hv.DisplayName -ne ('Shello owned '+$hvName)) { throw 'An unrelated Hyper-V firewall rule has this name.' }
        if ($hv) { Remove-NetFirewallHyperVRule -Name $hvName }
        $remote=if ($Plan.Direct) { $Plan.Remote } else { $Plan.Gateway }
        New-NetFirewallHyperVRule -Name $hvName -DisplayName ('Shello owned '+$hvName) -Direction Inbound -VMCreatorId '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}' -Protocol TCP -LocalPorts $Port -RemoteAddresses $remote | Out-Null
    }
}
function Remove-PairNetwork($Plan) {
    if (-not $Plan.Direct) {
        $current=Get-PortProxy $Plan.Address $Plan.Pair.Port
        if ($current -and $current.Target -eq $Plan.Pair.Target -and $current.TargetPort -eq $Plan.Pair.TargetPort) {
            [void](Invoke-ShelloCommand 'netsh.exe' @('interface','portproxy','delete','v4tov4',"listenaddress=$($Plan.Address)","listenport=$($Plan.Pair.Port)"))
        }
    }
    $rule=Get-NetFirewallRule -Name $Plan.PairRule -ErrorAction SilentlyContinue
    if ($rule -and $rule.Group -eq $Plan.Group) { Remove-NetFirewallRule -Name $Plan.PairRule }
    if (Get-Command Remove-NetFirewallHyperVRule -ErrorAction SilentlyContinue) { $hv=Get-NetFirewallHyperVRule -Name ($Plan.PairRule+'-HV') -ErrorAction SilentlyContinue;if($hv -and $hv.DisplayName -eq ('Shello owned '+$Plan.PairRule+'-HV')){Remove-NetFirewallHyperVRule -Name ($Plan.PairRule+'-HV')} }
}
function Invoke-NetworkLease($Plan) {
    if (-not (Test-PairAddress $Plan.Address) -or -not (Test-PairAddress $Plan.Ssh.Target) -or $Plan.Ssh.Port -eq $Plan.Pair.Port -or $Plan.Ssh.Port -lt 1024 -or $Plan.Pair.Port -lt 1024 -or $Plan.Ssh.Port -gt 65535 -or $Plan.Pair.Port -gt 65535 -or $Plan.LeaseSeconds -lt 1 -or $Plan.LeaseSeconds -gt 780) { throw 'Invalid network plan.' }
    $previous=$null
    if (Test-Path -LiteralPath $Plan.State) { $previous=Get-Content -Raw -LiteralPath $Plan.State | ConvertFrom-Json }
    $existingSsh=Get-PortProxy $Plan.Address $Plan.Ssh.Port
    Assert-ProxyOwnership $existingSsh $previous $null
    if (Get-PortProxy $Plan.Address $Plan.Pair.Port) { throw 'Pairing port is already forwarded; choose another -PairPort.' }
    foreach ($endpoint in @($Plan.Ssh,$Plan.Pair)) {
        $listeners=@(Get-NetTCPConnection -State Listen -LocalPort $endpoint.Port -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -in @($Plan.Address,'0.0.0.0','::') })
        if ($listeners.Count -gt 0 -and -not (Get-PortProxy $Plan.Address $endpoint.Port)) { throw 'A Windows listener occupies the requested port.' }
        if (@($listeners|Where-Object{$_.LocalAddress -in @('0.0.0.0','::')}).Count -gt 0) { throw 'A wildcard Windows listener occupies the requested port.' }
    }
    try {
        if ($Plan.Direct -and $existingSsh) { [void](Invoke-ShelloCommand 'netsh.exe' @('interface','portproxy','delete','v4tov4',"listenaddress=$($Plan.Address)","listenport=$($Plan.Ssh.Port)"));Remove-Item -LiteralPath $Plan.State -ErrorAction SilentlyContinue }
        if (-not $Plan.Direct) { if((Get-Service iphlpsvc).Status -ne 'Running'){Start-Service iphlpsvc};Set-ShelloProxy $Plan.Ssh; Write-PrivateJson $Plan.State $Plan.Ssh; Set-ShelloProxy $Plan.Pair }
        Set-ShelloFirewall $Plan.SshRule $Plan $Plan.Ssh.Port
        Set-ShelloFirewall $Plan.PairRule $Plan $Plan.Pair.Port
        Write-PrivateJson $Plan.Ack @{ready=$true}
        $deadline=[DateTime]::UtcNow.AddSeconds($Plan.LeaseSeconds)
        while (-not (Test-Path -LiteralPath $Plan.Stop) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 250 }
    } finally { Remove-PairNetwork $Plan }
}
function Start-ElevatedAction([string]$Action,[string]$Plan) {
    $args=@('-NoProfile','-ExecutionPolicy','Bypass','-File',$PSCommandPath,'-MachineAction',$Action,'-PlanFile',$Plan)
    return Start-Process -FilePath 'powershell.exe' -ArgumentList (Join-NativeArguments $args) -Verb RunAs -PassThru
}
function Invoke-MachineAction([string]$Action,[string]$Path) {
    $plan=Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    try {
        if (-not (Test-ShelloAdministrator)) { throw 'This machine configuration action requires administrator authorization.' }
        if ($Action -eq 'install-wsl') {
            $r=Invoke-ShelloCommand 'wsl.exe' @('--install','--no-distribution')
            Write-PrivateJson $plan.Ack @{ready=($r.Code -eq 0);code=$r.Code;message=$r.Text}
        } elseif ($Action -eq 'network') { Invoke-NetworkLease $plan }
        else { throw 'Unknown machine action.' }
    } catch { Write-PrivateJson $plan.Ack @{ready=$false;code=1;message=$_.Exception.Message}; throw }
}
function Test-ForwardedTls([string]$Address,[int]$Port,[string]$Pin) {
    $client=New-Object Net.Sockets.TcpClient
    try {
        $task=$client.ConnectAsync($Address,$Port)
        if (-not $task.Wait(2000)) { return $false }
        $callback={param($sender,$certificate,$chain,$errors) return $true}
        $stream=New-Object Net.Security.SslStream($client.GetStream(),$false,$callback)
        try {
            $stream.ReadTimeout=3000;$stream.WriteTimeout=3000
            $auth=$stream.AuthenticateAsClientAsync($Address)
            if(-not $auth.Wait(3000)){return $false}
            $auth.GetAwaiter().GetResult()
            $sha=[Security.Cryptography.SHA256]::Create()
            try { $actual=([BitConverter]::ToString($sha.ComputeHash($stream.RemoteCertificate.GetRawCertData()))).Replace('-','').ToLowerInvariant();return $actual -eq $Pin } finally { $sha.Dispose() }
        } finally { $stream.Dispose() }
    } catch { return $false } finally { $client.Dispose() }
}
function Invoke-ShelloPairing {
    if ($env:OS -ne 'Windows_NT') { throw 'Run pair.ps1 on Windows; use companion/pair.sh on macOS/Linux.' }
    if ($SshPort -eq $PairPort) { throw '-SshPort and -PairPort must differ.' }
    if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) { throw 'WSL is unavailable. Install WSL2 on a supported Windows host.' }
    $session=Join-Path $env:LOCALAPPDATA ('Shello\pair-sessions\'+[Guid]::NewGuid().ToString('N'))
    Protect-ShelloFolder $session
    $process=$null;$lease=$null;$plan=$null;$bundle=$null
    try {
        $status=Invoke-ShelloCommand 'wsl.exe' @('--status')
        if ($status.Code -ne 0) {
            if ($Check) { Write-ShelloEvent 'prerequisite_missing' @{name='WSL2';message='Install WSL2, reboot if requested, then rerun.'};return 2 }
            $installPlan=Join-Path $session 'install.json';$ack=Join-Path $session 'install-result.json'
            Write-PrivateJson $installPlan @{Ack=$ack}
            $child=Start-ElevatedAction 'install-wsl' $installPlan;$child.WaitForExit()
            if(-not(Test-Path -LiteralPath $ack)){throw 'WSL installation did not return a result.'}
            $installed=Get-Content -Raw -LiteralPath $ack|ConvertFrom-Json
            if($installed.code -notin @(0,3010)){throw $installed.message}
            Write-ShelloEvent 'restart_required' @{message='WSL setup ran. Complete any requested Windows restart, then rerun the same command.'};return 77
        }
        $names=Invoke-Wsl @('--list','--quiet')
        if (@($names -split "`r?`n" | Where-Object { $_.Trim() -eq $Distribution }).Count -ne 1) {
            if ($Check) { Write-ShelloEvent 'prerequisite_missing' @{name=$Distribution};return 2 }
            [void](Invoke-Wsl @('--install','--distribution',$Distribution,'--no-launch'))
            Write-ShelloEvent 'initialization_required' @{message="Open wsl.exe -d $Distribution, create your ordinary Linux user, then rerun."};return 77
        }
        $versions=Invoke-Wsl @('--list','--verbose')
        Assert-Wsl2Distribution $versions $Distribution
        $linuxUser=(Invoke-Wsl @('-d',$Distribution,'--exec','id','-un')).Trim()
        if ($linuxUser -notmatch '^[a-z_][a-z0-9_-]{0,31}$' -or $linuxUser -eq 'root') { Write-ShelloEvent 'initialization_required' @{message="Initialize an ordinary Linux user with wsl.exe -d $Distribution, then rerun."};return 77 }
        $endpoint=Get-WindowsEndpoint $Network $HostAddress
        if ($Check) { Write-ShelloEvent 'checked' @{distribution=$Distribution;linux_user=$linuxUser;host=$endpoint.Address};return 0 }
        foreach ($name in $script:ShelloBundle.Keys) { [IO.File]::WriteAllBytes((Join-Path $session $name),[Convert]::FromBase64String($script:ShelloBundle[$name])) }
        $bundle=(Invoke-Wsl @('-d',$Distribution,'--exec','wslpath','-a',$session)).Trim()
        $owner=Get-ShelloHash ([Security.Principal.WindowsIdentity]::GetCurrent().User.Value+'|'+$Distribution+'|'+$linuxUser+'|'+$SshPort)
        $prepared=Invoke-Wsl @('-d',$Distribution,'-u','root','--exec','bash',"$bundle/wsl-host.sh",$linuxUser,[string]$SshPort,$owner)
        $backend=($prepared -split "`n" | Select-Object -Last 1) | ConvertFrom-Json
        $direct=@($backend.addresses) -contains $endpoint.Address
        $group='Shello-WSL-'+(Get-ShelloHash ($owner+'|'+$endpoint.Address))
        $stateRoot=Join-Path $env:LOCALAPPDATA 'Shello\network';Protect-ShelloFolder $stateRoot
        $plan=[pscustomobject]@{Address=$endpoint.Address;Remote=$endpoint.Remote;Direct=$direct;Gateway=$backend.gateway;Group=$group;
            Ssh=[pscustomobject]@{Address=$endpoint.Address;Port=$SshPort;Target=$backend.address;TargetPort=$SshPort};
            Pair=[pscustomobject]@{Address=$endpoint.Address;Port=$PairPort;Target=$backend.address;TargetPort=$PairPort};
            SshRule=$group+'-SSH';PairRule=$group+'-Pair-'+[Guid]::NewGuid().ToString('N');
            State=(Join-Path $stateRoot ($group+'.json'));Ack=(Join-Path $session 'network-result.json');Stop=(Join-Path $session 'stop');LeaseSeconds=($Ttl+180)}
        $planPath=Join-Path $session 'network.json';Write-PrivateJson $planPath $plan
        $lease=Start-ElevatedAction 'network' $planPath
        $deadline=[DateTime]::UtcNow.AddSeconds(90)
        while (-not (Test-Path -LiteralPath $plan.Ack)) { if ($lease.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Network setup did not complete. Accept system authorization and retry.' };Start-Sleep -Milliseconds 250 }
        $ack=Get-Content -Raw -LiteralPath $plan.Ack | ConvertFrom-Json
        if (-not $ack.ready) { throw $ack.message }
        if (-not $backend.running) {
            $daemonArgs=@('-d',$Distribution,'-u','root','--exec','/usr/sbin/sshd','-D','-e','-f',$backend.config)
            # A Windows wsl.exe foreground command keeps this specific Linux SSH process alive.
            [void](Start-Process -FilePath 'wsl.exe' -ArgumentList (Join-NativeArguments $daemonArgs) -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $stateRoot ($owner+'-sshd.log')))
            Start-Sleep -Seconds 1
        }
        $label=$env:COMPUTERNAME+' / '+$Distribution+' (WSL2)'
        $args=@('-d',$Distribution,'-u',$linuxUser,'--exec','python3',"$bundle/start_pairing.py",'--launcher',"$bundle/pair.sh",'--host',$endpoint.Address,'--network',$Network,'--bind','0.0.0.0','--port',[string]$PairPort,'--ssh-port',[string]$SshPort,'--name',$label,'--ttl',[string]$Ttl)
        $process=New-ShelloProcess 'wsl.exe' $args;$errorTask=$process.StandardError.ReadToEndAsync()
        while ($null -ne ($line=$process.StandardOutput.ReadLine())) {
            $event=$line | ConvertFrom-Json
            if ($event.event -eq 'ready') {
                if (-not (Test-ForwardedTls $endpoint.Address $PairPort $event.certificate_sha256)) { throw 'Windows forwarded HTTPS certificate check failed; no QR will be shown.' }
                $png=Invoke-Wsl @('-d',$Distribution,'-u',$linuxUser,'--exec','base64','-w0',$event.qr_image)
                $image=Join-Path $session 'qr.png';[IO.File]::WriteAllBytes($image,[Convert]::FromBase64String($png))
                Write-ShelloEvent 'ready' @{qr_image=$image;expires_at_unix=$event.expires_at_unix;host=$endpoint.Address;ssh_port=$SshPort;distribution=$Distribution}
                if (-not $Json) {
                    Write-Host 'Shello > + > QR pairing. Scan this code on your own phone:'
                    $ascii=Invoke-Wsl @('-d',$Distribution,'-u',$linuxUser,'--exec',$event.renderer_python,"$bundle/render_qr.py",$event.qr_payload_file,$event.qr_image,'--ascii');Write-Host $ascii
                }
            } else { [Console]::Out.WriteLine($line) }
        }
        $process.WaitForExit();if($process.ExitCode -notin @(0,2)){Write-ShelloEvent 'failed' @{message=('WSL pairing process exited with code '+$process.ExitCode+'. Rerun with -Check and inspect WSL connectivity.')}};return $process.ExitCode
    } finally {
        if ($plan) { [IO.File]::WriteAllText($plan.Stop,'stop') }
        if ($process -and -not $process.HasExited) { $process.Kill() }
        if ($process) { $process.Dispose() }
        if ($lease -and -not $lease.HasExited) { [void]$lease.WaitForExit(10000) }
        # The SSH daemon and owned SSH forwarding stay available after pairing.
        # The non-secret daemon log lives separately from short-lived QR assets.
        Remove-Item -LiteralPath $session -Recurse -Force -ErrorAction SilentlyContinue
    }
}
if ($MyInvocation.InvocationName -ne '.') {
    try {
        if ($MachineAction) { Invoke-MachineAction $MachineAction $PlanFile;exit 0 }
        exit (Invoke-ShelloPairing)
    } catch { Write-ShelloEvent 'failed' @{message=$_.Exception.Message};exit 1 }
}
