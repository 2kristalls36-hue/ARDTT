# RouterOS 7 config for RB951 (2.4 GHz only, 5x Ethernet)
# Converted from RB952Ui-5ac2nD / RouterOS 7.22.3 export
# model = RB951Ui-2nD (also applies to RB951G-2HnD / RB951Ui-2HnD)
#
# LAN: 192.168.6.0/24  gateway 192.168.6.1  DHCP 192.168.6.10-192.168.6.100
# WG:  10.77.0.10/24 on wg1  peer 217.26.26.9:443  keepalive 21s
# ISP / firewall / ru-direct / failover scripts — same as source
#
# Import:
#   Connect via MAC WinBox, then:
#   /system reset-configuration no-defaults=yes skip-backup=yes
#   Upload this file, then: /import file-name=RB951.rsc
#
# After import set Wi-Fi password (source export has no PSK):
# /interface wireless security-profiles set [find name=profile1] wpa2-pre-shared-key="PASSWORD"
#
/interface bridge
add name=bridge1
/interface wireguard
add listen-port=9527 mtu=1420 name=wg1 private-key=\
    "uMW+UYQx6SvQZykAoJdY7H/YaVyU+3J+/LJvBrAwBkI="
/interface list
add name=WAN
add name=LAN
/interface wireless security-profiles
set [ find default=yes ] supplicant-identity=MikroTik
add authentication-types=wpa2-psk mode=dynamic-keys name=profile1 \
    supplicant-identity=""
/interface wireless
set [ find default-name=wlan1 ] band=2ghz-onlyn country=china disabled=no \
    distance=indoors frequency=2472 installation=indoor mode=ap-bridge \
    security-profile=profile1 ssid=Dorokhina tx-power=14 tx-power-mode=\
    all-rates-fixed wireless-protocol=802.11 wps-mode=\
    push-button-virtual-only
/ip pool
add name=dhcp_bridge ranges=192.168.6.10-192.168.6.100
/ip dhcp-server
add address-pool=dhcp_bridge interface=bridge1 name=dhcp1
/routing table
add fib name=isp-direct
/system script
add dont-require-permissions=no name=update-wan-gateway owner=admin policy=\
    ftp,reboot,read,write,policy,test,password,sniff,sensitive,romon source=":\
    local gw \"\"\r\
    \n:do {\r\
    \n  :set gw [/ip dhcp-client get [find where interface=ether1 and status=b\
    ound] gateway]\r\
    \n} on-error={}\r\
    \n:if ([:len \$gw] = 0) do={ :return }\r\
    \n:foreach id in=[/ip route find where static] do={\r\
    \n  :local g [/ip route get \$id gateway]\r\
    \n  :local c [/ip route get \$id comment]\r\
    \n  :if (\$c = \"VPN\") do={}\r\
    \n  :if (\$g = \"wg1\") do={}\r\
    \n  :if (\$g = \"ether1\" or \$g = \"192.168.6.1\") do={\r\
    \n    :do { /ip route set \$id gateway=\$gw } on-error={}\r\
    \n  }\r\
    \n}\r\
    \n:log info (\"WAN gateway updated to \" . \$gw)\r\
    \n"
add dont-require-permissions=no name=route_failover_led owner=admin policy=\
    ftp,reboot,read,write,policy,test,password,sniff,sensitive,romon source=":\
    local CFGvpnRouteComment \"VPN\"\r\
    \n    \
    \n:local CFGispRouteComment \"ISP\"\r\
    \n    \
    \n:local CFGvpnTestInterface \"\"\r\
    \n    \
    \n:local CFGispTestInterface \"ether1\"\r\
    \n    \
    \n:local CFGvpnTestAddress \"1.1.1.1\"\r\
    \n    \
    \n:local CFGispTestAddress \"1.1.1.1\"\r\
    \n    \
    \n:local CFGpingCount 3\r\
    \n    \
    \n:local CFGpingInterval \"300ms\"\r\
    \n    \
    \n:local CFGswitchDelay \"3s\"\r\
    \n    \
    \n:local CFGlogPrefix \"switch-route\"\r\
    \n    \
    \n:local CFGledISP \"led1\"\r\
    \n    \
    \n:local CFGledVPN \"led2\"\r\
    \n    \
    \n:local CFGledRun \"led5\"\r\
    \n    \
    \n:local routeSwitched false\r\
    \n    \
    \n\r\
    \n    \
    \n:do { /system leds set [find leds=\$CFGledRun] disabled=no type=on } on-\
    error={}\r\
    \n    \
    \n\r\
    \n    \
    \n:local vpnRouteId [/ip route find where comment=\$CFGvpnRouteComment]\r\
    \n    \
    \n:local ispRouteId [/ip route find where comment=\$CFGispRouteComment]\r\
    \n    \
    \n\r\
    \n    \
    \n:if ([:len \$vpnRouteId] = 0) do={\r\
    \n    \
    \n    :log error \"\$CFGlogPrefix: route with comment='\$CFGvpnRouteCommen\
    t' not found\"\r\
    \n    \
    \n    :error \"route-not-found\"\r\
    \n    \
    \n}\r\
    \n    \
    \n:if ([:len \$ispRouteId] = 0) do={\r\
    \n    \
    \n    :log error \"\$CFGlogPrefix: route with comment='\$CFGispRouteCommen\
    t' not found\"\r\
    \n    \
    \n    :error \"route-not-found\"\r\
    \n    \
    \n}\r\
    \n    \
    \n:if ([:len \$vpnRouteId] > 1) do={\r\
    \n    \
    \n    :log error \"\$CFGlogPrefix: multiple routes with comment='\$CFGvpnR\
    outeComment'\"\r\
    \n    \
    \n    :error \"ambiguous-route\"\r\
    \n    \
    \n}\r\
    \n    \
    \n:if ([:len \$ispRouteId] > 1) do={\r\
    \n    \
    \n    :log error \"\$CFGlogPrefix: multiple routes with comment='\$CFGispR\
    outeComment'\"\r\
    \n    \
    \n    :error \"ambiguous-route\"\r\
    \n    \
    \n}\r\
    \n    \
    \n\r\
    \n    \
    \n:local vpnRouteDisabled [/ip route get \$vpnRouteId disabled]\r\
    \n    \
    \n:local ispRouteDisabled [/ip route get \$ispRouteId disabled]\r\
    \n    \
    \n:local vpnGw [/ip route get \$vpnRouteId gateway]\r\
    \n    \
    \n:local ispGw [/ip route get \$ispRouteId gateway]\r\
    \n    \
    \n\r\
    \n    \
    \n:local vpnIf \$CFGvpnTestInterface\r\
    \n    \
    \n:if ([:len \$vpnIf] = 0) do={\r\
    \n    \
    \n    :if ([:len [/interface find where name=\$vpnGw]] > 0) do={ :set vpnI\
    f \$vpnGw }\r\
    \n    \
    \n}\r\
    \n    \
    \n\r\
    \n    \
    \n:local vpnAlive false\r\
    \n    \
    \n:do {\r\
    \n    \
    \n    :local vpnPing 0\r\
    \n    \
    \n    :if ([:len \$vpnIf] > 0) do={\r\
    \n    \
    \n        :set vpnPing [/ping address=\$CFGvpnTestAddress count=\$CFGpingC\
    ount interval=\$CFGpingInterval interface=\$vpnIf]\r\
    \n    \
    \n    }\r\
    \n    \
    \n    :set vpnAlive (\$vpnPing > 0)\r\
    \n    \
    \n} on-error={ :set vpnAlive false }\r\
    \n    \
    \n\r\
    \n    \
    \n:local ispAlive false\r\
    \n    \
    \n:do {\r\
    \n    \
    \n    :local ispPing 0\r\
    \n    \
    \n    :if ([:len [/interface find where name=\$ispGw]] = 0) do={\r\
    \n    \
    \n        :set ispPing [/ping address=\$ispGw count=2 interval=\$CFGpingIn\
    terval interface=\$CFGispTestInterface]\r\
    \n    \
    \n    }\r\
    \n    \
    \n    :if (\$ispPing = 0) do={\r\
    \n    \
    \n        :set ispPing [/ping address=\$CFGispTestAddress count=\$CFGpingC\
    ount interval=\$CFGpingInterval interface=\$CFGispTestInterface]\r\
    \n    \
    \n    }\r\
    \n    \
    \n    :set ispAlive (\$ispPing > 0)\r\
    \n    \
    \n} on-error={ :set ispAlive false }\r\
    \n    \
    \n\r\
    \n    \
    \n:if (\$vpnAlive) do={\r\
    \n    \
    \n    :if (\$vpnRouteDisabled or (!\$ispRouteDisabled)) do={\r\
    \n    \
    \n        /ip route set \$vpnRouteId disabled=no\r\
    \n    \
    \n        /ip route set \$ispRouteId disabled=yes\r\
    \n    \
    \n        :set routeSwitched true\r\
    \n    \
    \n        :log info \"\$CFGlogPrefix: VPN is UP -> enable VPN, disable ISP\
    \"\r\
    \n    \
    \n    }\r\
    \n    \
    \n} else={\r\
    \n    \
    \n    :if (\$ispAlive) do={\r\
    \n    \
    \n        :if ((!\$vpnRouteDisabled) or \$ispRouteDisabled) do={\r\
    \n    \
    \n            /ip route set \$vpnRouteId disabled=yes\r\
    \n    \
    \n            /ip route set \$ispRouteId disabled=no\r\
    \n    \
    \n            :set routeSwitched true\r\
    \n    \
    \n            :log warning \"\$CFGlogPrefix: VPN is DOWN, ISP is UP -> dis\
    able VPN, enable ISP\"\r\
    \n    \
    \n        }\r\
    \n    \
    \n    } else={\r\
    \n    \
    \n        :log error \"\$CFGlogPrefix: VPN and ISP probes failed\"\r\
    \n    \
    \n    }\r\
    \n    \
    \n}\r\
    \n    \
    \n\r\
    \n    \
    \n:if (\$routeSwitched) do={ :delay \$CFGswitchDelay }\r\
    \n    \
    \n\r\
    \n    \
    \n:if (\$ispAlive) do={\r\
    \n    \
    \n    :do { /system leds set [find leds=\$CFGledISP] disabled=no type=on }\
    \_on-error={\r\
    \n    \
    \n        /system leds set [find leds=\$CFGledISP] disabled=no type=interf\
    ace-activity interface=\$CFGispTestInterface\r\
    \n    \
    \n    }\r\
    \n    \
    \n} else={\r\
    \n    \
    \n    :do { /system leds set [find leds=\$CFGledISP] disabled=no type=off \
    } on-error={\r\
    \n    \
    \n        /system leds set [find leds=\$CFGledISP] disabled=yes\r\
    \n    \
    \n    }\r\
    \n    \
    \n}\r\
    \n    \
    \n\r\
    \n    \
    \n:if (\$vpnAlive and ([:len \$vpnIf] > 0)) do={\r\
    \n    \
    \n    :do { /system leds set [find leds=\$CFGledVPN] disabled=no type=on }\
    \_on-error={\r\
    \n    \
    \n        /system leds set [find leds=\$CFGledVPN] disabled=no type=interf\
    ace-activity interface=\$vpnIf\r\
    \n    \
    \n    }\r\
    \n    \
    \n} else={\r\
    \n    \
    \n    :do { /system leds set [find leds=\$CFGledVPN] disabled=no type=off \
    } on-error={\r\
    \n    \
    \n        /system leds set [find leds=\$CFGledVPN] disabled=yes\r\
    \n    \
    \n    }\r\
    \n    \
    \n}\r\
    \n    \
    \n\r\
    \n    \
    \n:do { /system leds set [find leds=\$CFGledRun] disabled=no type=off } on\
    -error={\r\
    \n    \
    \n    :do { /system leds set [find leds=\$CFGledRun] disabled=yes } on-err\
    or={}\r\
    \n    \
    \n}\r\
    \n"
add dont-require-permissions=no name=update-ru-direct owner=admin policy=\
    read,write,test,sensitive source="\
    \n\
    \n:local listUrl \"http://10.77.0.1:8088/ru-direct.rsc\"\
    \n\
    \n:local tmpFile \"ru-direct.rsc\"\
    \n\
    \n\
    \n\
    \n:do {\
    \n\
    \n  /tool fetch url=\$listUrl dst-path=\$tmpFile mode=http keep-result=yes\
    \n\
    \n} on-error={\
    \n\
    \n  :log error \"ru-direct: fetch failed\"\
    \n\
    \n  :error \"fetch failed\"\
    \n\
    \n}\
    \n\
    \n\
    \n\
    \n:delay 2s\
    \n\
    \n:if ([:len [/file find name=\$tmpFile]] = 0) do={\
    \n\
    \n  :log error \"ru-direct: file missing\"\
    \n\
    \n  :error \"missing\"\
    \n\
    \n}\
    \n\
    \n\
    \n\
    \n:do {\
    \n\
    \n  /import file-name=\$tmpFile\
    \n\
    \n  :log info (\"ru-direct: imported, count=\" . [/ip firewall address-lis\
    t print count-only where list=ru-direct])\
    \n\
    \n} on-error={\
    \n\
    \n  :log error \"ru-direct: import failed\"\
    \n\
    \n  :error \"import failed\"\
    \n\
    \n}\
    \n\
    \n\
    \n\
    \n:do { /file remove [find name=\$tmpFile] } on-error={}\
    \n\
    \n"
/interface bridge port
add bridge=bridge1 interface=ether2
add bridge=bridge1 interface=ether3
add bridge=bridge1 interface=ether4
add bridge=bridge1 interface=ether5
add bridge=bridge1 interface=wlan1
/ip neighbor discovery-settings
set discover-interface-list=!WAN
/ipv6 settings
set disable-ipv6=yes
/interface list member
add interface=bridge1 list=LAN
add interface=ether1 list=WAN
/interface wireguard peers
add allowed-address=0.0.0.0/0 client-allowed-address=::/0 endpoint-address=\
    217.26.26.9 endpoint-port=443 interface=wg1 name=peer1 \
    persistent-keepalive=21s public-key=\
    "kVPBj62PpMaG0Zx50r+H+f2H+F+7sKbXYGS2EkMsdBo="
/ip address
add address=192.168.6.1/24 interface=bridge1 network=192.168.6.0
add address=10.77.0.10/24 interface=wg1 network=10.77.0.0
/ip cloud
set ddns-update-interval=1m
/ip dhcp-client
add add-default-route=no interface=ether1 name=client1 script=":if (\$bound = \
    1) do={ :delay 3s; /system script run update-wan-gateway; /system script r\
    un route_failover_led } else={ :log warning \"ether1: DHCP lost\" }" \
    use-peer-dns=no use-peer-ntp=no
/ip dhcp-server network
add address=192.168.6.0/24 dns-server=192.168.6.1 gateway=192.168.6.1
/ip dns
set allow-remote-requests=yes servers=8.8.8.8,8.8.4.4
/ip firewall address-list
add address=2.17.251.115 comment=ru-mirror list=ru-direct
add address=2.17.251.123 comment=ru-mirror list=ru-direct
add address=3.33.130.190 comment=ru-mirror list=ru-direct
add address=3.33.193.101 comment=ru-mirror list=ru-direct
add address=3.33.251.168 comment=ru-mirror list=ru-direct
add address=3.234.68.252 comment=ru-mirror list=ru-direct
add address=3.255.14.143 comment=ru-mirror list=ru-direct
add address=5.45.66.150 comment=ru-mirror list=ru-direct
add address=5.45.192.0/18 comment=ru-mirror list=ru-direct
add address=5.61.16.0/21 comment=ru-mirror list=ru-direct
add address=5.61.232.0/21 comment=ru-mirror list=ru-direct
add address=5.101.40.0/22 comment=ru-mirror list=ru-direct
add address=5.161.236.232 comment=ru-mirror list=ru-direct
add address=5.181.60.0/22 comment=ru-mirror list=ru-direct
add address=5.187.6.161 comment=ru-mirror list=ru-direct
add address=5.188.140.0/22 comment=ru-mirror list=ru-direct
add address=5.255.192.0/18 comment=ru-mirror list=ru-direct
add address=8.6.112.0 comment=ru-mirror list=ru-direct
add address=8.6.112.6 comment=ru-mirror list=ru-direct
add address=8.47.69.0 comment=ru-mirror list=ru-direct
add address=8.47.69.6 comment=ru-mirror list=ru-direct
add address=8.209.124.241 comment=ru-mirror list=ru-direct
add address=13.248.169.48 comment=ru-mirror list=ru-direct
add address=13.248.243.5 comment=ru-mirror list=ru-direct
add address=15.197.148.33 comment=ru-mirror list=ru-direct
add address=15.197.225.128 comment=ru-mirror list=ru-direct
add address=15.197.246.237 comment=ru-mirror list=ru-direct
add address=20.50.2.94 comment=ru-mirror list=ru-direct
add address=23.128.64.150 comment=ru-mirror list=ru-direct
add address=23.158.72.62 comment=ru-mirror list=ru-direct
add address=23.227.38.65 comment=ru-mirror list=ru-direct
add address=31.31.205.163 comment=ru-mirror list=ru-direct
add address=31.44.8.0/21 comment=ru-mirror list=ru-direct
add address=31.130.140.0/22 comment=ru-mirror list=ru-direct
add address=31.177.64.0/20 comment=ru-mirror list=ru-direct
add address=31.177.80.0/23 comment=ru-mirror list=ru-direct
add address=31.177.84.0/22 comment=ru-mirror list=ru-direct
add address=31.177.88.0/21 comment=ru-mirror list=ru-direct
add address=31.177.104.0/22 comment=ru-mirror list=ru-direct
add address=34.117.59.81 comment=ru-mirror list=ru-direct
add address=34.117.176.22 comment=ru-mirror list=ru-direct
add address=34.160.111.145 comment=ru-mirror list=ru-direct
add address=34.195.135.204 comment=ru-mirror list=ru-direct
add address=34.202.68.214 comment=ru-mirror list=ru-direct
add address=34.242.85.232 comment=ru-mirror list=ru-direct
add address=34.243.93.217 comment=ru-mirror list=ru-direct
add address=34.251.223.83 comment=ru-mirror list=ru-direct
add address=35.157.26.135 comment=ru-mirror list=ru-direct
add address=37.9.64.0/18 comment=ru-mirror list=ru-direct
add address=37.48.65.182 comment=ru-mirror list=ru-direct
add address=37.139.32.0/22 comment=ru-mirror list=ru-direct
add address=37.139.40.0/22 comment=ru-mirror list=ru-direct
add address=37.140.128.0/18 comment=ru-mirror list=ru-direct
add address=37.220.160.0/21 comment=ru-mirror list=ru-direct
add address=37.230.168.0/23 comment=ru-mirror list=ru-direct
add address=37.230.172.0/22 comment=ru-mirror list=ru-direct
add address=37.230.240.0/24 comment=ru-mirror list=ru-direct
add address=39.101.78.31 comment=ru-mirror list=ru-direct
add address=43.134.36.9 comment=ru-mirror list=ru-direct
add address=44.196.25.30 comment=ru-mirror list=ru-direct
add address=44.219.72.9 comment=ru-mirror list=ru-direct
add address=44.223.250.126 comment=ru-mirror list=ru-direct
add address=45.14.173.0/24 comment=ru-mirror list=ru-direct
add address=45.84.128.0/22 comment=ru-mirror list=ru-direct
add address=45.89.26.245 comment=ru-mirror list=ru-direct
add address=45.130.41.40 comment=ru-mirror list=ru-direct
add address=45.136.20.0/22 comment=ru-mirror list=ru-direct
add address=45.137.112.0/23 comment=ru-mirror list=ru-direct
add address=45.137.115.0/24 comment=ru-mirror list=ru-direct
add address=46.17.203.154 comment=ru-mirror list=ru-direct
add address=46.17.206.11 comment=ru-mirror list=ru-direct
add address=46.21.244.0/22 comment=ru-mirror list=ru-direct
add address=46.28.17.61 comment=ru-mirror list=ru-direct
add address=46.38.54.70 comment=ru-mirror list=ru-direct
add address=46.173.21.0/24 comment=ru-mirror list=ru-direct
add address=46.226.122.0/24 comment=ru-mirror list=ru-direct
add address=46.235.184.0/21 comment=ru-mirror list=ru-direct
add address=46.243.209.0/24 comment=ru-mirror list=ru-direct
add address=46.243.210.0/23 comment=ru-mirror list=ru-direct
add address=46.245.234.0/24 comment=ru-mirror list=ru-direct
add address=47.246.2.176/31 comment=ru-mirror list=ru-direct
add address=47.246.133.141 comment=ru-mirror list=ru-direct
add address=47.246.133.142/31 comment=ru-mirror list=ru-direct
add address=47.246.133.144 comment=ru-mirror list=ru-direct
add address=47.246.133.147 comment=ru-mirror list=ru-direct
add address=47.246.133.148 comment=ru-mirror list=ru-direct
add address=51.250.0.0/17 comment=ru-mirror list=ru-direct
add address=52.0.42.46 comment=ru-mirror list=ru-direct
add address=52.19.109.11 comment=ru-mirror list=ru-direct
add address=52.20.84.62 comment=ru-mirror list=ru-direct
add address=52.29.79.39 comment=ru-mirror list=ru-direct
add address=52.42.174.9 comment=ru-mirror list=ru-direct
add address=52.57.221.121 comment=ru-mirror list=ru-direct
add address=52.202.215.126 comment=ru-mirror list=ru-direct
add address=52.210.8.76 comment=ru-mirror list=ru-direct
add address=52.211.30.170 comment=ru-mirror list=ru-direct
add address=52.223.46.195 comment=ru-mirror list=ru-direct
add address=54.172.31.170 comment=ru-mirror list=ru-direct
add address=58.211.15.146/31 comment=ru-mirror list=ru-direct
add address=58.211.15.200/31 comment=ru-mirror list=ru-direct
add address=58.211.15.202 comment=ru-mirror list=ru-direct
add address=58.211.15.211 comment=ru-mirror list=ru-direct
add address=62.84.112.0/20 comment=ru-mirror list=ru-direct
add address=62.118.254.152 comment=ru-mirror list=ru-direct
add address=62.217.160.0/20 comment=ru-mirror list=ru-direct
add address=63.176.8.218 comment=ru-mirror list=ru-direct
add address=64.29.17.195 comment=ru-mirror list=ru-direct
add address=64.190.63.222 comment=ru-mirror list=ru-direct
add address=65.108.151.63 comment=ru-mirror list=ru-direct
add address=67.23.251.180 comment=ru-mirror list=ru-direct
add address=68.183.128.200 comment=ru-mirror list=ru-direct
add address=76.76.21.21 comment=ru-mirror list=ru-direct
add address=76.223.54.146 comment=ru-mirror list=ru-direct
add address=76.223.105.230 comment=ru-mirror list=ru-direct
add address=77.75.152.0/21 comment=ru-mirror list=ru-direct
add address=77.88.0.0/18 comment=ru-mirror list=ru-direct
add address=77.105.176.97 comment=ru-mirror list=ru-direct
add address=77.220.216.0/24 comment=ru-mirror list=ru-direct
add address=77.220.218.0/24 comment=ru-mirror list=ru-direct
add address=77.220.220.0/23 comment=ru-mirror list=ru-direct
add address=77.220.223.0/24 comment=ru-mirror list=ru-direct
add address=77.222.33.0/24 comment=ru-mirror list=ru-direct
add address=77.222.34.0/24 comment=ru-mirror list=ru-direct
add address=77.233.222.9 comment=ru-mirror list=ru-direct
add address=77.242.14.0/24 comment=ru-mirror list=ru-direct
add address=78.41.104.0/21 comment=ru-mirror list=ru-direct
add address=78.155.196.152 comment=ru-mirror list=ru-direct
add address=78.155.198.0/24 comment=ru-mirror list=ru-direct
add address=79.137.132.0/24 comment=ru-mirror list=ru-direct
add address=79.137.139.0/24 comment=ru-mirror list=ru-direct
add address=79.137.140.0/24 comment=ru-mirror list=ru-direct
add address=79.137.142.0/24 comment=ru-mirror list=ru-direct
add address=79.137.157.0/24 comment=ru-mirror list=ru-direct
add address=79.137.164.0/24 comment=ru-mirror list=ru-direct
add address=79.137.167.0/24 comment=ru-mirror list=ru-direct
add address=79.137.174.0/23 comment=ru-mirror list=ru-direct
add address=79.137.180.0/24 comment=ru-mirror list=ru-direct
add address=79.137.183.0/24 comment=ru-mirror list=ru-direct
add address=79.137.240.0/21 comment=ru-mirror list=ru-direct
add address=79.174.64.0/21 comment=ru-mirror list=ru-direct
add address=79.174.72.0/22 comment=ru-mirror list=ru-direct
add address=79.174.76.0/24 comment=ru-mirror list=ru-direct
add address=80.66.92.0/22 comment=ru-mirror list=ru-direct
add address=80.67.43.1 comment=ru-mirror list=ru-direct
add address=80.67.43.17 comment=ru-mirror list=ru-direct
add address=80.67.43.33 comment=ru-mirror list=ru-direct
add address=80.67.43.49 comment=ru-mirror list=ru-direct
add address=80.67.43.65 comment=ru-mirror list=ru-direct
add address=80.67.43.81 comment=ru-mirror list=ru-direct
add address=80.79.78.0/24 comment=ru-mirror list=ru-direct
add address=80.93.56.0/22 comment=ru-mirror list=ru-direct
add address=80.94.82.0/23 comment=ru-mirror list=ru-direct
add address=81.26.176.0/20 comment=ru-mirror list=ru-direct
add address=81.30.199.238 comment=ru-mirror list=ru-direct
add address=81.161.98.0/23 comment=ru-mirror list=ru-direct
add address=81.200.127.11 comment=ru-mirror list=ru-direct
add address=82.26.122.0/24 comment=ru-mirror list=ru-direct
add address=82.68.121.182 comment=ru-mirror list=ru-direct
add address=82.138.40.0/24 comment=ru-mirror list=ru-direct
add address=82.202.189.125 comment=ru-mirror list=ru-direct
add address=82.202.189.136 comment=ru-mirror list=ru-direct
add address=82.202.190.99 comment=ru-mirror list=ru-direct
add address=82.202.190.153 comment=ru-mirror list=ru-direct
add address=82.202.190.240 comment=ru-mirror list=ru-direct
add address=82.202.191.110 comment=ru-mirror list=ru-direct
add address=82.202.197.91 comment=ru-mirror list=ru-direct
add address=82.204.188.43 comment=ru-mirror list=ru-direct
add address=83.166.232.0/21 comment=ru-mirror list=ru-direct
add address=83.166.248.0/21 comment=ru-mirror list=ru-direct
add address=83.217.216.0/22 comment=ru-mirror list=ru-direct
add address=83.222.28.0/22 comment=ru-mirror list=ru-direct
add address=84.23.52.0/22 comment=ru-mirror list=ru-direct
add address=84.201.128.0/18 comment=ru-mirror list=ru-direct
add address=84.252.128.0/20 comment=ru-mirror list=ru-direct
add address=84.252.144.0/21 comment=ru-mirror list=ru-direct
add address=84.252.152.0/24 comment=ru-mirror list=ru-direct
add address=84.252.160.0/19 comment=ru-mirror list=ru-direct
add address=85.21.78.93 comment=ru-mirror list=ru-direct
add address=85.118.177.0/24 comment=ru-mirror list=ru-direct
add address=85.118.181.0/24 comment=ru-mirror list=ru-direct
add address=85.118.182.0/23 comment=ru-mirror list=ru-direct
add address=85.119.149.50 comment=ru-mirror list=ru-direct
add address=85.119.149.68 comment=ru-mirror list=ru-direct
add address=85.142.115.0/24 comment=ru-mirror list=ru-direct
add address=85.142.251.0/24 comment=ru-mirror list=ru-direct
add address=85.158.185.0/24 comment=ru-mirror list=ru-direct
add address=85.158.188.0/22 comment=ru-mirror list=ru-direct
add address=85.192.32.0/22 comment=ru-mirror list=ru-direct
add address=85.198.76.0/22 comment=ru-mirror list=ru-direct
add address=85.198.107.0/24 comment=ru-mirror list=ru-direct
add address=85.198.115.115 comment=ru-mirror list=ru-direct
add address=85.198.115.116 comment=ru-mirror list=ru-direct
add address=87.106.44.200 comment=ru-mirror list=ru-direct
add address=87.226.162.216 comment=ru-mirror list=ru-direct
add address=87.239.104.0/21 comment=ru-mirror list=ru-direct
add address=87.240.128.0/18 comment=ru-mirror list=ru-direct
add address=87.242.112.0/22 comment=ru-mirror list=ru-direct
add address=87.245.154.0/24 comment=ru-mirror list=ru-direct
add address=87.250.224.0/19 comment=ru-mirror list=ru-direct
add address=87.251.92.0/23 comment=ru-mirror list=ru-direct
add address=89.104.76.0/22 comment=ru-mirror list=ru-direct
add address=89.104.80.0/21 comment=ru-mirror list=ru-direct
add address=89.104.88.0/22 comment=ru-mirror list=ru-direct
add address=89.104.92.0/24 comment=ru-mirror list=ru-direct
add address=89.104.112.0/23 comment=ru-mirror list=ru-direct
add address=89.108.121.179 comment=ru-mirror list=ru-direct
add address=89.111.128.0/20 comment=ru-mirror list=ru-direct
add address=89.111.144.0/21 comment=ru-mirror list=ru-direct
add address=89.111.156.0/22 comment=ru-mirror list=ru-direct
add address=89.111.160.0/21 comment=ru-mirror list=ru-direct
add address=89.111.176.0/20 comment=ru-mirror list=ru-direct
add address=89.169.128.0/18 comment=ru-mirror list=ru-direct
add address=89.191.229.40 comment=ru-mirror list=ru-direct
add address=89.208.84.0/22 comment=ru-mirror list=ru-direct
add address=89.208.196.0/22 comment=ru-mirror list=ru-direct
add address=89.208.208.0/22 comment=ru-mirror list=ru-direct
add address=89.208.216.0/21 comment=ru-mirror list=ru-direct
add address=89.208.228.0/22 comment=ru-mirror list=ru-direct
add address=89.221.228.0/22 comment=ru-mirror list=ru-direct
add address=89.221.232.0/21 comment=ru-mirror list=ru-direct
add address=89.223.9.0/24 comment=ru-mirror list=ru-direct
add address=89.223.20.0/24 comment=ru-mirror list=ru-direct
add address=89.232.188.0/23 comment=ru-mirror list=ru-direct
add address=89.248.230.0/24 comment=ru-mirror list=ru-direct
add address=90.156.148.0/22 comment=ru-mirror list=ru-direct
add address=90.156.212.0/22 comment=ru-mirror list=ru-direct
add address=90.156.216.0/22 comment=ru-mirror list=ru-direct
add address=90.156.232.0/21 comment=ru-mirror list=ru-direct
add address=90.156.244.0/22 comment=ru-mirror list=ru-direct
add address=91.107.80.0/22 comment=ru-mirror list=ru-direct
add address=91.108.123.169 comment=ru-mirror list=ru-direct
add address=91.189.112.0/21 comment=ru-mirror list=ru-direct
add address=91.191.213.54 comment=ru-mirror list=ru-direct
add address=91.194.16.0/23 comment=ru-mirror list=ru-direct
add address=91.194.226.0/23 comment=ru-mirror list=ru-direct
add address=91.197.176.0/22 comment=ru-mirror list=ru-direct
add address=91.199.205.0/24 comment=ru-mirror list=ru-direct
add address=91.203.225.0/24 comment=ru-mirror list=ru-direct
add address=91.206.100.0/23 comment=ru-mirror list=ru-direct
add address=91.206.127.39 comment=ru-mirror list=ru-direct
add address=91.206.127.224 comment=ru-mirror list=ru-direct
add address=91.206.127.236 comment=ru-mirror list=ru-direct
add address=91.206.127.243 comment=ru-mirror list=ru-direct
add address=91.208.166.0/24 comment=ru-mirror list=ru-direct
add address=91.208.232.0/24 comment=ru-mirror list=ru-direct
add address=91.208.248.0/24 comment=ru-mirror list=ru-direct
add address=91.209.76.0/24 comment=ru-mirror list=ru-direct
add address=91.212.64.0/24 comment=ru-mirror list=ru-direct
add address=91.213.144.237 comment=ru-mirror list=ru-direct
add address=91.215.37.245 comment=ru-mirror list=ru-direct
add address=91.215.37.248 comment=ru-mirror list=ru-direct
add address=91.215.38.248 comment=ru-mirror list=ru-direct
add address=91.215.41.74 comment=ru-mirror list=ru-direct
add address=91.216.69.0/24 comment=ru-mirror list=ru-direct
add address=91.217.20.0/23 comment=ru-mirror list=ru-direct
add address=91.217.102.0/23 comment=ru-mirror list=ru-direct
add address=91.217.180.0/24 comment=ru-mirror list=ru-direct
add address=91.217.194.0/24 comment=ru-mirror list=ru-direct
add address=91.217.227.173 comment=ru-mirror list=ru-direct
add address=91.217.233.0/24 comment=ru-mirror list=ru-direct
add address=91.218.132.0/22 comment=ru-mirror list=ru-direct
add address=91.219.224.0/22 comment=ru-mirror list=ru-direct
add address=91.221.164.24 comment=ru-mirror list=ru-direct
add address=91.221.164.42 comment=ru-mirror list=ru-direct
add address=91.221.164.66 comment=ru-mirror list=ru-direct
add address=91.221.164.93 comment=ru-mirror list=ru-direct
add address=91.221.165.158 comment=ru-mirror list=ru-direct
add address=91.221.198.0/23 comment=ru-mirror list=ru-direct
add address=91.223.63.99 comment=ru-mirror list=ru-direct
add address=91.223.93.0/24 comment=ru-mirror list=ru-direct
add address=91.227.91.0/24 comment=ru-mirror list=ru-direct
add address=91.230.107.0/24 comment=ru-mirror list=ru-direct
add address=91.231.132.0/22 comment=ru-mirror list=ru-direct
add address=91.232.93.39 comment=ru-mirror list=ru-direct
add address=91.236.48.0/22 comment=ru-mirror list=ru-direct
add address=92.38.217.0/24 comment=ru-mirror list=ru-direct
add address=92.113.18.138 comment=ru-mirror list=ru-direct
add address=92.255.1.0/24 comment=ru-mirror list=ru-direct
add address=92.255.3.0/24 comment=ru-mirror list=ru-direct
add address=92.255.13.0/24 comment=ru-mirror list=ru-direct
add address=92.255.15.0/24 comment=ru-mirror list=ru-direct
add address=92.255.16.0/24 comment=ru-mirror list=ru-direct
add address=92.255.58.0/23 comment=ru-mirror list=ru-direct
add address=92.255.112.0/20 comment=ru-mirror list=ru-direct
add address=93.77.160.0/19 comment=ru-mirror list=ru-direct
add address=93.92.113.0/24 comment=ru-mirror list=ru-direct
add address=93.93.88.0/22 comment=ru-mirror list=ru-direct
add address=93.93.92.0/23 comment=ru-mirror list=ru-direct
add address=93.93.94.0/24 comment=ru-mirror list=ru-direct
add address=93.157.186.0/24 comment=ru-mirror list=ru-direct
add address=93.158.128.0/18 comment=ru-mirror list=ru-direct
add address=93.186.224.0/20 comment=ru-mirror list=ru-direct
add address=93.189.87.0/24 comment=ru-mirror list=ru-direct
add address=94.26.255.99 comment=ru-mirror list=ru-direct
add address=94.41.0.10 comment=ru-mirror list=ru-direct
add address=94.79.5.0/24 comment=ru-mirror list=ru-direct
add address=94.79.6.0/24 comment=ru-mirror list=ru-direct
add address=94.79.19.57 comment=ru-mirror list=ru-direct
add address=94.79.51.0/24 comment=ru-mirror list=ru-direct
add address=94.100.176.0/20 comment=ru-mirror list=ru-direct
add address=94.124.200.0/21 comment=ru-mirror list=ru-direct
add address=94.126.204.0/22 comment=ru-mirror list=ru-direct
add address=94.131.190.0/23 comment=ru-mirror list=ru-direct
add address=94.139.244.0/22 comment=ru-mirror list=ru-direct
add address=94.139.248.0/22 comment=ru-mirror list=ru-direct
add address=95.85.16.212 comment=ru-mirror list=ru-direct
add address=95.105.60.50 comment=ru-mirror list=ru-direct
add address=95.108.128.0/17 comment=ru-mirror list=ru-direct
add address=95.129.232.159 comment=ru-mirror list=ru-direct
add address=95.129.232.161 comment=ru-mirror list=ru-direct
add address=95.129.232.180 comment=ru-mirror list=ru-direct
add address=95.129.236.157 comment=ru-mirror list=ru-direct
add address=95.142.192.0/20 comment=ru-mirror list=ru-direct
add address=95.163.32.0/19 comment=ru-mirror list=ru-direct
add address=95.163.92.66 comment=ru-mirror list=ru-direct
add address=95.163.133.0/24 comment=ru-mirror list=ru-direct
add address=95.163.135.67 comment=ru-mirror list=ru-direct
add address=95.163.138.151 comment=ru-mirror list=ru-direct
add address=95.163.138.155 comment=ru-mirror list=ru-direct
add address=95.163.138.157 comment=ru-mirror list=ru-direct
add address=95.163.159.0/24 comment=ru-mirror list=ru-direct
add address=95.163.180.0/22 comment=ru-mirror list=ru-direct
add address=95.163.208.0/21 comment=ru-mirror list=ru-direct
add address=95.163.216.0/22 comment=ru-mirror list=ru-direct
add address=95.163.244.134/31 comment=ru-mirror list=ru-direct
add address=95.163.248.0/21 comment=ru-mirror list=ru-direct
add address=95.167.23.7 comment=ru-mirror list=ru-direct
add address=95.167.154.115 comment=ru-mirror list=ru-direct
add address=95.167.245.92 comment=ru-mirror list=ru-direct
add address=95.173.128.89 comment=ru-mirror list=ru-direct
add address=95.173.128.90 comment=ru-mirror list=ru-direct
add address=95.181.177.3 comment=ru-mirror list=ru-direct
add address=95.181.177.13 comment=ru-mirror list=ru-direct
add address=95.181.177.29 comment=ru-mirror list=ru-direct
add address=95.181.181.254 comment=ru-mirror list=ru-direct
add address=95.181.182.184 comment=ru-mirror list=ru-direct
add address=95.213.0.0/17 comment=ru-mirror list=ru-direct
add address=98.98.216.0/24 comment=ru-mirror list=ru-direct
add address=98.129.229.12 comment=ru-mirror list=ru-direct
add address=99.83.183.127 comment=ru-mirror list=ru-direct
add address=99.84.152.54 comment=ru-mirror list=ru-direct
add address=99.84.152.93 comment=ru-mirror list=ru-direct
add address=99.84.152.113 comment=ru-mirror list=ru-direct
add address=99.84.152.115 comment=ru-mirror list=ru-direct
add address=103.76.52.0/22 comment=ru-mirror list=ru-direct
add address=104.16.184.241 comment=ru-mirror list=ru-direct
add address=104.16.185.241 comment=ru-mirror list=ru-direct
add address=104.17.190.234 comment=ru-mirror list=ru-direct
add address=104.17.191.234 comment=ru-mirror list=ru-direct
add address=104.19.222.79 comment=ru-mirror list=ru-direct
add address=104.19.223.79 comment=ru-mirror list=ru-direct
add address=104.21.8.147 comment=ru-mirror list=ru-direct
add address=104.21.10.206 comment=ru-mirror list=ru-direct
add address=104.21.12.220 comment=ru-mirror list=ru-direct
add address=104.21.16.55 comment=ru-mirror list=ru-direct
add address=104.21.16.72 comment=ru-mirror list=ru-direct
add address=104.21.18.144 comment=ru-mirror list=ru-direct
add address=104.21.19.21 comment=ru-mirror list=ru-direct
add address=104.21.19.235 comment=ru-mirror list=ru-direct
add address=104.21.28.49 comment=ru-mirror list=ru-direct
add address=104.21.34.74 comment=ru-mirror list=ru-direct
add address=104.21.39.145 comment=ru-mirror list=ru-direct
add address=104.21.42.44 comment=ru-mirror list=ru-direct
add address=104.21.48.191 comment=ru-mirror list=ru-direct
add address=104.21.49.135 comment=ru-mirror list=ru-direct
add address=104.21.52.46 comment=ru-mirror list=ru-direct
add address=104.21.54.91 comment=ru-mirror list=ru-direct
add address=104.21.56.183 comment=ru-mirror list=ru-direct
add address=104.21.68.98 comment=ru-mirror list=ru-direct
add address=104.21.69.162 comment=ru-mirror list=ru-direct
add address=104.21.72.158 comment=ru-mirror list=ru-direct
add address=104.21.73.97 comment=ru-mirror list=ru-direct
add address=104.21.74.56 comment=ru-mirror list=ru-direct
add address=104.21.74.198 comment=ru-mirror list=ru-direct
add address=104.21.74.214 comment=ru-mirror list=ru-direct
add address=104.21.80.215 comment=ru-mirror list=ru-direct
add address=104.21.85.189 comment=ru-mirror list=ru-direct
add address=104.21.87.155 comment=ru-mirror list=ru-direct
add address=104.21.92.106 comment=ru-mirror list=ru-direct
add address=104.21.94.136 comment=ru-mirror list=ru-direct
add address=104.21.94.219 comment=ru-mirror list=ru-direct
add address=104.26.0.228 comment=ru-mirror list=ru-direct
add address=104.26.1.228 comment=ru-mirror list=ru-direct
add address=104.26.4.4 comment=ru-mirror list=ru-direct
add address=104.26.10.4 comment=ru-mirror list=ru-direct
add address=104.26.11.4 comment=ru-mirror list=ru-direct
add address=104.26.12.31 comment=ru-mirror list=ru-direct
add address=104.26.13.31 comment=ru-mirror list=ru-direct
add address=104.166.180.0/23 comment=ru-mirror list=ru-direct
add address=104.167.242.109 comment=ru-mirror list=ru-direct
add address=104.250.32.0/22 comment=ru-mirror list=ru-direct
add address=104.250.38.0/23 comment=ru-mirror list=ru-direct
add address=104.250.46.0/23 comment=ru-mirror list=ru-direct
add address=104.250.48.0/21 comment=ru-mirror list=ru-direct
add address=104.250.56.0/22 comment=ru-mirror list=ru-direct
add address=107.155.51.0/24 comment=ru-mirror list=ru-direct
add address=107.155.52.0/23 comment=ru-mirror list=ru-direct
add address=108.132.190.215 comment=ru-mirror list=ru-direct
add address=109.70.24.0/21 comment=ru-mirror list=ru-direct
add address=109.120.180.0/22 comment=ru-mirror list=ru-direct
add address=109.120.188.0/22 comment=ru-mirror list=ru-direct
add address=109.172.74.0/24 comment=ru-mirror list=ru-direct
add address=109.207.0.0/20 comment=ru-mirror list=ru-direct
add address=109.235.160.0/21 comment=ru-mirror list=ru-direct
add address=109.238.88.146 comment=ru-mirror list=ru-direct
add address=109.238.88.243 comment=ru-mirror list=ru-direct
add address=109.238.90.138 comment=ru-mirror list=ru-direct
add address=109.238.90.201 comment=ru-mirror list=ru-direct
add address=109.238.90.239 comment=ru-mirror list=ru-direct
add address=111.88.144.0/20 comment=ru-mirror list=ru-direct
add address=111.88.240.0/20 comment=ru-mirror list=ru-direct
add address=115.238.23.240 comment=ru-mirror list=ru-direct
add address=116.202.113.61 comment=ru-mirror list=ru-direct
add address=116.203.83.202 comment=ru-mirror list=ru-direct
add address=119.96.6.153 comment=ru-mirror list=ru-direct
add address=119.96.7.7 comment=ru-mirror list=ru-direct
add address=120.92.192.0/23 comment=ru-mirror list=ru-direct
add address=128.140.168.0/21 comment=ru-mirror list=ru-direct
add address=130.49.224.0/19 comment=ru-mirror list=ru-direct
add address=130.193.32.0/19 comment=ru-mirror list=ru-direct
add address=132.226.8.169 comment=ru-mirror list=ru-direct
add address=132.226.247.73 comment=ru-mirror list=ru-direct
add address=132.243.176.0/22 comment=ru-mirror list=ru-direct
add address=134.119.216.174 comment=ru-mirror list=ru-direct
add address=136.243.156.168 comment=ru-mirror list=ru-direct
add address=138.16.192.0/20 comment=ru-mirror list=ru-direct
add address=138.16.240.0/20 comment=ru-mirror list=ru-direct
add address=140.205.77.240 comment=ru-mirror list=ru-direct
add address=140.207.188.119 comment=ru-mirror list=ru-direct
add address=141.8.128.0/18 comment=ru-mirror list=ru-direct
add address=143.42.24.160 comment=ru-mirror list=ru-direct
add address=146.59.166.237 comment=ru-mirror list=ru-direct
add address=146.185.208.0/22 comment=ru-mirror list=ru-direct
add address=146.185.240.0/22 comment=ru-mirror list=ru-direct
add address=149.28.31.24 comment=ru-mirror list=ru-direct
add address=151.236.110.187 comment=ru-mirror list=ru-direct
add address=151.236.118.252 comment=ru-mirror list=ru-direct
add address=153.51.96.0/19 comment=ru-mirror list=ru-direct
add address=155.212.192.0/20 comment=ru-mirror list=ru-direct
add address=155.212.223.69 comment=ru-mirror list=ru-direct
add address=155.212.234.130 comment=ru-mirror list=ru-direct
add address=155.212.234.239 comment=ru-mirror list=ru-direct
add address=155.212.235.180 comment=ru-mirror list=ru-direct
add address=157.22.224.0/22 comment=ru-mirror list=ru-direct
add address=157.90.31.134 comment=ru-mirror list=ru-direct
add address=158.101.44.242 comment=ru-mirror list=ru-direct
add address=158.160.0.0/16 comment=ru-mirror list=ru-direct
add address=158.247.204.5 comment=ru-mirror list=ru-direct
add address=159.89.102.253 comment=ru-mirror list=ru-direct
add address=159.89.180.98 comment=ru-mirror list=ru-direct
add address=161.104.104.0/21 comment=ru-mirror list=ru-direct
add address=162.55.51.87 comment=ru-mirror list=ru-direct
add address=162.55.60.2 comment=ru-mirror list=ru-direct
add address=162.159.134.22 comment=ru-mirror list=ru-direct
add address=162.159.135.22 comment=ru-mirror list=ru-direct
add address=162.159.140.159 comment=ru-mirror list=ru-direct
add address=167.235.241.236 comment=ru-mirror list=ru-direct
add address=169.197.116.0/23 comment=ru-mirror list=ru-direct
add address=171.105.61.2/31 comment=ru-mirror list=ru-direct
add address=171.105.61.4/31 comment=ru-mirror list=ru-direct
add address=171.105.62.2 comment=ru-mirror list=ru-direct
add address=172.66.0.157 comment=ru-mirror list=ru-direct
add address=172.67.69.28 comment=ru-mirror list=ru-direct
add address=172.67.70.70 comment=ru-mirror list=ru-direct
add address=172.67.72.100 comment=ru-mirror list=ru-direct
add address=172.67.75.172 comment=ru-mirror list=ru-direct
add address=172.67.140.137 comment=ru-mirror list=ru-direct
add address=172.67.144.63 comment=ru-mirror list=ru-direct
add address=172.67.144.78 comment=ru-mirror list=ru-direct
add address=172.67.152.176 comment=ru-mirror list=ru-direct
add address=172.67.153.183 comment=ru-mirror list=ru-direct
add address=172.67.154.92 comment=ru-mirror list=ru-direct
add address=172.67.155.71 comment=ru-mirror list=ru-direct
add address=172.67.155.175 comment=ru-mirror list=ru-direct
add address=172.67.156.14 comment=ru-mirror list=ru-direct
add address=172.67.156.27 comment=ru-mirror list=ru-direct
add address=172.67.160.84 comment=ru-mirror list=ru-direct
add address=172.67.163.103 comment=ru-mirror list=ru-direct
add address=172.67.163.127 comment=ru-mirror list=ru-direct
add address=172.67.164.128 comment=ru-mirror list=ru-direct
add address=172.67.166.229 comment=ru-mirror list=ru-direct
add address=172.67.168.79 comment=ru-mirror list=ru-direct
add address=172.67.168.106 comment=ru-mirror list=ru-direct
add address=172.67.170.206 comment=ru-mirror list=ru-direct
add address=172.67.182.83 comment=ru-mirror list=ru-direct
add address=172.67.184.121 comment=ru-mirror list=ru-direct
add address=172.67.188.139 comment=ru-mirror list=ru-direct
add address=172.67.190.120 comment=ru-mirror list=ru-direct
add address=172.67.191.233 comment=ru-mirror list=ru-direct
add address=172.67.193.226 comment=ru-mirror list=ru-direct
add address=172.67.195.96 comment=ru-mirror list=ru-direct
add address=172.67.199.248 comment=ru-mirror list=ru-direct
add address=172.67.206.55 comment=ru-mirror list=ru-direct
add address=172.67.209.71 comment=ru-mirror list=ru-direct
add address=172.67.210.32 comment=ru-mirror list=ru-direct
add address=172.67.210.91 comment=ru-mirror list=ru-direct
add address=176.57.65.141 comment=ru-mirror list=ru-direct
add address=176.57.65.201 comment=ru-mirror list=ru-direct
add address=176.57.67.176 comment=ru-mirror list=ru-direct
add address=176.101.88.0/21 comment=ru-mirror list=ru-direct
add address=176.112.168.0/21 comment=ru-mirror list=ru-direct
add address=176.114.120.0/21 comment=ru-mirror list=ru-direct
add address=178.22.88.0/21 comment=ru-mirror list=ru-direct
add address=178.57.95.0/24 comment=ru-mirror list=ru-direct
add address=178.72.139.109 comment=ru-mirror list=ru-direct
add address=178.130.128.0/23 comment=ru-mirror list=ru-direct
add address=178.154.128.0/17 comment=ru-mirror list=ru-direct
add address=178.170.186.60 comment=ru-mirror list=ru-direct
add address=178.177.13.147 comment=ru-mirror list=ru-direct
add address=178.177.13.149 comment=ru-mirror list=ru-direct
add address=178.177.13.217 comment=ru-mirror list=ru-direct
add address=178.178.127.20 comment=ru-mirror list=ru-direct
add address=178.208.149.0/24 comment=ru-mirror list=ru-direct
add address=178.210.64.0/19 comment=ru-mirror list=ru-direct
add address=178.213.78.0/24 comment=ru-mirror list=ru-direct
add address=178.237.16.0/20 comment=ru-mirror list=ru-direct
add address=178.237.33.50 comment=ru-mirror list=ru-direct
add address=178.248.232.0/21 comment=ru-mirror list=ru-direct
add address=180.163.29.151 comment=ru-mirror list=ru-direct
add address=185.5.136.0/22 comment=ru-mirror list=ru-direct
add address=185.6.244.0/22 comment=ru-mirror list=ru-direct
add address=185.12.152.144 comment=ru-mirror list=ru-direct
add address=185.16.148.0/22 comment=ru-mirror list=ru-direct
add address=185.16.244.0/22 comment=ru-mirror list=ru-direct
add address=185.17.168.0/22 comment=ru-mirror list=ru-direct
add address=185.26.112.0/22 comment=ru-mirror list=ru-direct
add address=185.29.130.0/24 comment=ru-mirror list=ru-direct
add address=185.32.184.0/22 comment=ru-mirror list=ru-direct
add address=185.32.248.0/22 comment=ru-mirror list=ru-direct
add address=185.35.144.0/22 comment=ru-mirror list=ru-direct
add address=185.54.220.248 comment=ru-mirror list=ru-direct
add address=185.62.100.0/24 comment=ru-mirror list=ru-direct
add address=185.62.200.0/22 comment=ru-mirror list=ru-direct
add address=185.65.148.0/22 comment=ru-mirror list=ru-direct
add address=185.71.64.201 comment=ru-mirror list=ru-direct
add address=185.71.67.17 comment=ru-mirror list=ru-direct
add address=185.71.67.56 comment=ru-mirror list=ru-direct
add address=185.71.67.88 comment=ru-mirror list=ru-direct
add address=185.71.67.111 comment=ru-mirror list=ru-direct
add address=185.71.76.0/22 comment=ru-mirror list=ru-direct
add address=185.73.192.0/22 comment=ru-mirror list=ru-direct
add address=185.76.144.0/22 comment=ru-mirror list=ru-direct
add address=185.86.144.0/22 comment=ru-mirror list=ru-direct
add address=185.89.12.0/24 comment=ru-mirror list=ru-direct
add address=185.89.14.0/23 comment=ru-mirror list=ru-direct
add address=185.94.108.0/22 comment=ru-mirror list=ru-direct
add address=185.100.104.0/22 comment=ru-mirror list=ru-direct
add address=185.111.100.0/22 comment=ru-mirror list=ru-direct
add address=185.129.103.93 comment=ru-mirror list=ru-direct
add address=185.130.112.0/22 comment=ru-mirror list=ru-direct
add address=185.131.68.0/22 comment=ru-mirror list=ru-direct
add address=185.135.83.132 comment=ru-mirror list=ru-direct
add address=185.137.232.210/31 comment=ru-mirror list=ru-direct
add address=185.137.232.212 comment=ru-mirror list=ru-direct
add address=185.138.252.0/22 comment=ru-mirror list=ru-direct
add address=185.154.124.0/24 comment=ru-mirror list=ru-direct
add address=185.157.96.0/22 comment=ru-mirror list=ru-direct
add address=185.158.112.158 comment=ru-mirror list=ru-direct
add address=185.161.67.201 comment=ru-mirror list=ru-direct
add address=185.163.159.137 comment=ru-mirror list=ru-direct
add address=185.163.159.154 comment=ru-mirror list=ru-direct
add address=185.163.159.218 comment=ru-mirror list=ru-direct
add address=185.165.123.103 comment=ru-mirror list=ru-direct
add address=185.165.123.176 comment=ru-mirror list=ru-direct
add address=185.169.155.2 comment=ru-mirror list=ru-direct
add address=185.169.155.69 comment=ru-mirror list=ru-direct
add address=185.169.155.114 comment=ru-mirror list=ru-direct
add address=185.169.155.118 comment=ru-mirror list=ru-direct
add address=185.169.155.145 comment=ru-mirror list=ru-direct
add address=185.169.155.189 comment=ru-mirror list=ru-direct
add address=185.170.0.0/22 comment=ru-mirror list=ru-direct
add address=185.170.199.209 comment=ru-mirror list=ru-direct
add address=185.173.0.0/22 comment=ru-mirror list=ru-direct
add address=185.173.80.60 comment=ru-mirror list=ru-direct
add address=185.174.128.0/22 comment=ru-mirror list=ru-direct
add address=185.178.208.7 comment=ru-mirror list=ru-direct
add address=185.178.210.132/31 comment=ru-mirror list=ru-direct
add address=185.178.210.137 comment=ru-mirror list=ru-direct
add address=185.178.210.140 comment=ru-mirror list=ru-direct
add address=185.179.144.0/22 comment=ru-mirror list=ru-direct
add address=185.180.200.0/22 comment=ru-mirror list=ru-direct
add address=185.184.128.0/22 comment=ru-mirror list=ru-direct
add address=185.185.57.8 comment=ru-mirror list=ru-direct
add address=185.185.57.72 comment=ru-mirror list=ru-direct
add address=185.185.148.0/22 comment=ru-mirror list=ru-direct
add address=185.186.184.0/22 comment=ru-mirror list=ru-direct
add address=185.187.63.0/24 comment=ru-mirror list=ru-direct
add address=185.199.108.153 comment=ru-mirror list=ru-direct
add address=185.199.109.153 comment=ru-mirror list=ru-direct
add address=185.206.164.0/22 comment=ru-mirror list=ru-direct
add address=185.211.156.0/22 comment=ru-mirror list=ru-direct
add address=185.215.4.20 comment=ru-mirror list=ru-direct
add address=185.215.4.40 comment=ru-mirror list=ru-direct
add address=185.215.4.47 comment=ru-mirror list=ru-direct
add address=185.215.4.52 comment=ru-mirror list=ru-direct
add address=185.216.194.0/23 comment=ru-mirror list=ru-direct
add address=185.226.52.0/22 comment=ru-mirror list=ru-direct
add address=185.241.192.0/22 comment=ru-mirror list=ru-direct
add address=185.243.86.7 comment=ru-mirror list=ru-direct
add address=188.40.167.81 comment=ru-mirror list=ru-direct
add address=188.68.242.180 comment=ru-mirror list=ru-direct
add address=188.72.103.0/24 comment=ru-mirror list=ru-direct
add address=188.72.105.0/24 comment=ru-mirror list=ru-direct
add address=188.72.110.0/23 comment=ru-mirror list=ru-direct
add address=188.72.113.0/24 comment=ru-mirror list=ru-direct
add address=188.93.56.0/21 comment=ru-mirror list=ru-direct
add address=188.114.96.0 comment=ru-mirror list=ru-direct
add address=188.114.97.0 comment=ru-mirror list=ru-direct
add address=188.130.244.0/23 comment=ru-mirror list=ru-direct
add address=188.162.60.210/31 comment=ru-mirror list=ru-direct
add address=188.170.66.11 comment=ru-mirror list=ru-direct
add address=188.170.66.75 comment=ru-mirror list=ru-direct
add address=188.186.157.51 comment=ru-mirror list=ru-direct
add address=188.225.57.186 comment=ru-mirror list=ru-direct
add address=188.246.160.43 comment=ru-mirror list=ru-direct
add address=188.246.224.242 comment=ru-mirror list=ru-direct
add address=190.2.142.44 comment=ru-mirror list=ru-direct
add address=193.0.185.0/24 comment=ru-mirror list=ru-direct
add address=193.17.93.194 comment=ru-mirror list=ru-direct
add address=193.22.88.70 comment=ru-mirror list=ru-direct
add address=193.26.19.14 comment=ru-mirror list=ru-direct
add address=193.26.19.16 comment=ru-mirror list=ru-direct
add address=193.26.19.99 comment=ru-mirror list=ru-direct
add address=193.26.19.101 comment=ru-mirror list=ru-direct
add address=193.28.44.8 comment=ru-mirror list=ru-direct
add address=193.28.44.159 comment=ru-mirror list=ru-direct
add address=193.32.216.0/22 comment=ru-mirror list=ru-direct
add address=193.37.157.43 comment=ru-mirror list=ru-direct
add address=193.104.70.0/24 comment=ru-mirror list=ru-direct
add address=193.104.207.0/24 comment=ru-mirror list=ru-direct
add address=193.122.6.168 comment=ru-mirror list=ru-direct
add address=193.122.130.0 comment=ru-mirror list=ru-direct
add address=193.138.82.0/24 comment=ru-mirror list=ru-direct
add address=193.143.64.0/22 comment=ru-mirror list=ru-direct
add address=193.143.119.0/24 comment=ru-mirror list=ru-direct
add address=193.162.30.87 comment=ru-mirror list=ru-direct
add address=193.164.146.0/24 comment=ru-mirror list=ru-direct
add address=193.200.10.38 comment=ru-mirror list=ru-direct
add address=193.200.10.123 comment=ru-mirror list=ru-direct
add address=193.200.18.0/24 comment=ru-mirror list=ru-direct
add address=193.200.129.0/24 comment=ru-mirror list=ru-direct
add address=193.203.40.0/22 comment=ru-mirror list=ru-direct
add address=193.219.127.0/24 comment=ru-mirror list=ru-direct
add address=193.232.39.0/24 comment=ru-mirror list=ru-direct
add address=193.232.104.0/24 comment=ru-mirror list=ru-direct
add address=193.232.108.0/24 comment=ru-mirror list=ru-direct
add address=193.232.123.0/24 comment=ru-mirror list=ru-direct
add address=193.232.130.0/24 comment=ru-mirror list=ru-direct
add address=193.232.146.0/24 comment=ru-mirror list=ru-direct
add address=193.232.168.26/31 comment=ru-mirror list=ru-direct
add address=193.238.119.0/24 comment=ru-mirror list=ru-direct
add address=193.238.178.80 comment=ru-mirror list=ru-direct
add address=194.1.214.0/24 comment=ru-mirror list=ru-direct
add address=194.8.224.0/23 comment=ru-mirror list=ru-direct
add address=194.9.208.0/22 comment=ru-mirror list=ru-direct
add address=194.33.79.0/24 comment=ru-mirror list=ru-direct
add address=194.54.12.0/22 comment=ru-mirror list=ru-direct
add address=194.67.72.31 comment=ru-mirror list=ru-direct
add address=194.85.61.0/24 comment=ru-mirror list=ru-direct
add address=194.85.99.0/24 comment=ru-mirror list=ru-direct
add address=194.85.103.0/24 comment=ru-mirror list=ru-direct
add address=194.85.222.0/24 comment=ru-mirror list=ru-direct
add address=194.145.158.0/24 comment=ru-mirror list=ru-direct
add address=194.176.100.0/24 comment=ru-mirror list=ru-direct
add address=194.186.63.0/24 comment=ru-mirror list=ru-direct
add address=194.190.0.23 comment=ru-mirror list=ru-direct
add address=194.190.0.50 comment=ru-mirror list=ru-direct
add address=194.190.0.150 comment=ru-mirror list=ru-direct
add address=194.190.0.152 comment=ru-mirror list=ru-direct
add address=194.190.0.245 comment=ru-mirror list=ru-direct
add address=194.190.12.0/24 comment=ru-mirror list=ru-direct
add address=194.190.144.0/24 comment=ru-mirror list=ru-direct
add address=194.190.172.157 comment=ru-mirror list=ru-direct
add address=194.226.55.0/24 comment=ru-mirror list=ru-direct
add address=194.226.96.0/24 comment=ru-mirror list=ru-direct
add address=194.242.122.225 comment=ru-mirror list=ru-direct
add address=194.247.51.0/24 comment=ru-mirror list=ru-direct
add address=195.24.64.0/22 comment=ru-mirror list=ru-direct
add address=195.24.68.0/23 comment=ru-mirror list=ru-direct
add address=195.24.70.0/24 comment=ru-mirror list=ru-direct
add address=195.34.20.0/23 comment=ru-mirror list=ru-direct
add address=195.42.96.0/23 comment=ru-mirror list=ru-direct
add address=195.43.90.0/23 comment=ru-mirror list=ru-direct
add address=195.43.144.0/24 comment=ru-mirror list=ru-direct
add address=195.64.209.0/24 comment=ru-mirror list=ru-direct
add address=195.66.78.0/24 comment=ru-mirror list=ru-direct
add address=195.74.74.0/24 comment=ru-mirror list=ru-direct
add address=195.80.159.133 comment=ru-mirror list=ru-direct
add address=195.133.4.0/24 comment=ru-mirror list=ru-direct
add address=195.133.255.86 comment=ru-mirror list=ru-direct
add address=195.161.52.80 comment=ru-mirror list=ru-direct
add address=195.182.28.0/24 comment=ru-mirror list=ru-direct
add address=195.191.76.0/23 comment=ru-mirror list=ru-direct
add address=195.200.209.0/24 comment=ru-mirror list=ru-direct
add address=195.200.213.0/24 comment=ru-mirror list=ru-direct
add address=195.208.0.0/23 comment=ru-mirror list=ru-direct
add address=195.208.64.0/21 comment=ru-mirror list=ru-direct
add address=195.208.72.0/22 comment=ru-mirror list=ru-direct
add address=195.208.76.0/23 comment=ru-mirror list=ru-direct
add address=195.208.109.0/24 comment=ru-mirror list=ru-direct
add address=195.209.64.0/20 comment=ru-mirror list=ru-direct
add address=195.209.80.0/22 comment=ru-mirror list=ru-direct
add address=195.209.85.0/24 comment=ru-mirror list=ru-direct
add address=195.209.86.0/23 comment=ru-mirror list=ru-direct
add address=195.209.88.0/23 comment=ru-mirror list=ru-direct
add address=195.209.90.0/24 comment=ru-mirror list=ru-direct
add address=195.209.92.0/23 comment=ru-mirror list=ru-direct
add address=195.209.94.0/24 comment=ru-mirror list=ru-direct
add address=195.209.99.0/24 comment=ru-mirror list=ru-direct
add address=195.209.134.0/24 comment=ru-mirror list=ru-direct
add address=195.209.181.0/24 comment=ru-mirror list=ru-direct
add address=195.209.192.0/23 comment=ru-mirror list=ru-direct
add address=195.209.195.0/24 comment=ru-mirror list=ru-direct
add address=195.209.196.0/22 comment=ru-mirror list=ru-direct
add address=195.209.200.0/23 comment=ru-mirror list=ru-direct
add address=195.209.202.0/24 comment=ru-mirror list=ru-direct
add address=195.211.20.0/22 comment=ru-mirror list=ru-direct
add address=195.218.190.0/23 comment=ru-mirror list=ru-direct
add address=195.225.38.0/23 comment=ru-mirror list=ru-direct
add address=195.234.170.0/24 comment=ru-mirror list=ru-direct
add address=195.239.64.0/24 comment=ru-mirror list=ru-direct
add address=195.242.82.0/23 comment=ru-mirror list=ru-direct
add address=195.245.206.101 comment=ru-mirror list=ru-direct
add address=195.246.246.0/24 comment=ru-mirror list=ru-direct
add address=195.248.69.0/24 comment=ru-mirror list=ru-direct
add address=195.250.51.0/24 comment=ru-mirror list=ru-direct
add address=198.202.211.1 comment=ru-mirror list=ru-direct
add address=199.36.158.100 comment=ru-mirror list=ru-direct
add address=201.50.118.0/24 comment=ru-mirror list=ru-direct
add address=208.79.209.138 comment=ru-mirror list=ru-direct
add address=208.95.112.1 comment=ru-mirror list=ru-direct
add address=212.11.129.0/24 comment=ru-mirror list=ru-direct
add address=212.11.130.0/23 comment=ru-mirror list=ru-direct
add address=212.11.138.0/23 comment=ru-mirror list=ru-direct
add address=212.11.141.0/24 comment=ru-mirror list=ru-direct
add address=212.11.143.0/24 comment=ru-mirror list=ru-direct
add address=212.11.144.0/23 comment=ru-mirror list=ru-direct
add address=212.11.146.0/24 comment=ru-mirror list=ru-direct
add address=212.11.148.0/22 comment=ru-mirror list=ru-direct
add address=212.11.152.0/21 comment=ru-mirror list=ru-direct
add address=212.45.30.0/24 comment=ru-mirror list=ru-direct
add address=212.102.35.236 comment=ru-mirror list=ru-direct
add address=212.109.215.139 comment=ru-mirror list=ru-direct
add address=212.109.215.214 comment=ru-mirror list=ru-direct
add address=212.109.215.240 comment=ru-mirror list=ru-direct
add address=212.111.84.0/22 comment=ru-mirror list=ru-direct
add address=212.129.20.209 comment=ru-mirror list=ru-direct
add address=212.164.138.120/29 comment=ru-mirror list=ru-direct
add address=212.164.138.128/30 comment=ru-mirror list=ru-direct
add address=212.164.140.129 comment=ru-mirror list=ru-direct
add address=212.164.140.151 comment=ru-mirror list=ru-direct
add address=212.164.140.153 comment=ru-mirror list=ru-direct
add address=212.193.144.0/20 comment=ru-mirror list=ru-direct
add address=212.233.72.0/21 comment=ru-mirror list=ru-direct
add address=212.233.80.0/20 comment=ru-mirror list=ru-direct
add address=212.233.96.0/22 comment=ru-mirror list=ru-direct
add address=212.233.120.0/22 comment=ru-mirror list=ru-direct
add address=213.24.64.175 comment=ru-mirror list=ru-direct
add address=213.24.64.178 comment=ru-mirror list=ru-direct
add address=213.33.155.164 comment=ru-mirror list=ru-direct
add address=213.33.175.0/24 comment=ru-mirror list=ru-direct
add address=213.59.197.7 comment=ru-mirror list=ru-direct
add address=213.59.253.7 comment=ru-mirror list=ru-direct
add address=213.59.254.7 comment=ru-mirror list=ru-direct
add address=213.87.50.76 comment=ru-mirror list=ru-direct
add address=213.108.128.246 comment=ru-mirror list=ru-direct
add address=213.130.80.0/21 comment=ru-mirror list=ru-direct
add address=213.133.116.46 comment=ru-mirror list=ru-direct
add address=213.165.192.0/19 comment=ru-mirror list=ru-direct
add address=213.180.192.0/19 comment=ru-mirror list=ru-direct
add address=213.184.154.0/23 comment=ru-mirror list=ru-direct
add address=213.184.156.0/22 comment=ru-mirror list=ru-direct
add address=213.219.212.0/22 comment=ru-mirror list=ru-direct
add address=213.232.251.131 comment=ru-mirror list=ru-direct
add address=213.232.251.132 comment=ru-mirror list=ru-direct
add address=213.255.230.0/24 comment=ru-mirror list=ru-direct
add address=216.106.188.200 comment=ru-mirror list=ru-direct
add address=216.150.1.1 comment=ru-mirror list=ru-direct
add address=216.198.79.195 comment=ru-mirror list=ru-direct
add address=216.239.32.21 comment=ru-mirror list=ru-direct
add address=216.239.34.21 comment=ru-mirror list=ru-direct
add address=216.239.36.21 comment=ru-mirror list=ru-direct
add address=216.239.38.21 comment=ru-mirror list=ru-direct
add address=217.12.96.0/21 comment=ru-mirror list=ru-direct
add address=217.12.104.0/23 comment=ru-mirror list=ru-direct
add address=217.12.106.0/24 comment=ru-mirror list=ru-direct
add address=217.12.108.0/24 comment=ru-mirror list=ru-direct
add address=217.12.110.0/24 comment=ru-mirror list=ru-direct
add address=217.14.16.0/23 comment=ru-mirror list=ru-direct
add address=217.14.18.0/24 comment=ru-mirror list=ru-direct
add address=217.14.20.0/24 comment=ru-mirror list=ru-direct
add address=217.14.23.0/24 comment=ru-mirror list=ru-direct
add address=217.14.48.0/20 comment=ru-mirror list=ru-direct
add address=217.16.16.0/20 comment=ru-mirror list=ru-direct
add address=217.20.144.0/20 comment=ru-mirror list=ru-direct
add address=217.28.224.0/20 comment=ru-mirror list=ru-direct
add address=217.69.128.0/20 comment=ru-mirror list=ru-direct
add address=217.118.84.189 comment=ru-mirror list=ru-direct
add address=217.118.87.21 comment=ru-mirror list=ru-direct
add address=217.118.87.98 comment=ru-mirror list=ru-direct
add address=217.118.87.127 comment=ru-mirror list=ru-direct
add address=217.174.188.0/22 comment=ru-mirror list=ru-direct
add address=217.175.128.0/19 comment=ru-mirror list=ru-direct
add address=217.198.168.0/21 comment=ru-mirror list=ru-direct
/ip firewall filter
add action=accept chain=input comment="IN: accept established,related" \
    connection-state=established,related
add action=drop chain=input comment="IN: drop invalid" connection-state=\
    invalid
add action=accept chain=input comment="IN: allow ICMP from LAN" \
    in-interface-list=LAN protocol=icmp
add action=accept chain=input comment="IN: allow WinBox from LAN" dst-port=\
    8291 in-interface-list=LAN protocol=tcp
add action=accept chain=input comment="IN: allow DNS(udp) from LAN" dst-port=\
    53 in-interface-list=LAN protocol=udp
add action=accept chain=input comment="IN: allow DNS(tcp) from LAN" dst-port=\
    53 in-interface-list=LAN protocol=tcp
add action=accept chain=input comment="IN: allow DHCP server from LAN" \
    dst-port=67 in-interface-list=LAN protocol=udp
add action=accept chain=input comment="IN: allow management from WireGuard" \
    in-interface=wg1
add action=drop chain=input comment="IN: drop all from WAN" \
    in-interface-list=WAN
add action=accept chain=forward comment="FWD: accept established,related" \
    connection-state=established,related
add action=drop chain=forward comment="FWD: drop invalid" connection-state=\
    invalid
add action=drop chain=forward comment="FWD: drop WAN not dstnat" \
    connection-nat-state=!dstnat in-interface-list=WAN
add action=accept chain=forward comment="FWD: LAN to WAN" in-interface-list=\
    LAN out-interface-list=WAN
add action=accept chain=forward comment="FWD: LAN to WG" in-interface-list=\
    LAN out-interface=wg1
add action=accept chain=forward comment="FWD: WG to LAN" in-interface=wg1 \
    out-interface-list=LAN
add action=drop chain=forward comment="FWD: drop the rest"
/ip firewall mangle
add action=change-mss chain=forward comment="MSS clamp for ISP" new-mss=1460 \
    out-interface=ether1 protocol=tcp tcp-flags=syn
add action=change-mss chain=forward comment="MSS clamp for WG" new-mss=1380 \
    out-interface=wg1 protocol=tcp tcp-flags=syn
add action=mark-routing chain=prerouting comment="RU whitelist mark" \
    dst-address-list=ru-direct in-interface-list=LAN new-routing-mark=\
    isp-direct
/ip firewall nat
add action=accept chain=srcnat comment=\
    "NAT: no-srcnat to remote LAN 192.168.3.0/24" disabled=yes dst-address=\
    192.168.3.0/24 out-interface=wg1
add action=accept chain=srcnat comment=\
    "NAT: no-srcnat to remote LAN 192.168.10.0/24" disabled=yes dst-address=\
    192.168.10.0/24 out-interface=wg1
add action=accept chain=srcnat comment=\
    "NAT: no-srcnat to remote LAN 192.168.88.0/24" disabled=yes dst-address=\
    192.168.88.0/24 out-interface=wg1
add action=masquerade chain=srcnat comment="NAT: masquerade to WAN" \
    out-interface-list=WAN
add action=masquerade chain=srcnat comment=\
    "NAT: masquerade to WG (internet via VPN)" out-interface=wg1
/ip route
add comment=ISP disabled=yes distance=1 dst-address=0.0.0.0/0 gateway=\
    10.16.45.1 routing-table=main scope=30 target-scope=10
add comment=VPN disabled=no distance=1 dst-address=0.0.0.0/0 gateway=wg1 \
    routing-table=main scope=10 target-scope=5
add dst-address=192.168.10.0/24 gateway=10.77.0.1
add dst-address=192.168.88.0/24 gateway=10.77.0.1
add dst-address=192.168.3.0/24 gateway=10.77.0.1
add comment=engage.cloudflareclient.com disabled=no dst-address=\
    162.159.192.1/32 gateway=10.16.45.1 routing-table=main
add comment="HSR WDTT" disabled=no distance=1 dst-address=154.83.186.203/32 \
    gateway=10.16.45.1 routing-table=main scope=30 target-scope=10
add comment="Beget primary" dst-address=217.26.26.9/32 gateway=10.16.45.1 \
    scope=10
add dst-address=1.1.1.1/32 gateway=10.16.45.1 scope=10
add dst-address=8.8.8.8/32 gateway=10.16.45.1 scope=10
add comment=freedom dst-address=10.0.0.0/8 gateway=10.16.45.1 scope=10
add comment="RU whitelist via ISP" dst-address=0.0.0.0/0 gateway=10.16.45.1 \
    routing-table=isp-direct
/ip service
set ftp disabled=yes
set ssh disabled=yes
set telnet disabled=yes
set www disabled=yes
set winbox address=10.77.0.0/24,192.168.0.0/16
set api disabled=yes
set api-ssl disabled=yes
/routing rule
add action=lookup-only-in-table comment="RU whitelist direct" disabled=no \
    routing-mark=isp-direct table=isp-direct
/system clock
set time-zone-name=Europe/Moscow
/system identity
set name="Severniy 6"
/system scheduler
add interval=2m name=route_failover_led on-event=route_failover_led policy=\
    ftp,reboot,read,write,policy,test,password,sniff,sensitive,romon \
    start-time=startup
add interval=1d name=update-ru-direct on-event=update-ru-direct policy=\
    read,write,test,sensitive start-date=2026-08-12 start-time=06:30:00
