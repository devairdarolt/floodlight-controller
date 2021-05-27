#!/bin/sh
echo 'add gateway '

curl -X POST -d '{"gateway-name" : "mininet-gateway-1", "gateway-mac" : "aa:bb:cc:dd:ee:ff"}' http://localhost:8080/wm/routing/gateway

echo 'add interface to a gateway curl -s http://localhost:8080/wm/routing/gateway'
curl -X POST -d '{interfaces" : [{ "interface-name" : "interface-1", "interface-ip" : "10.0.0.1",  "interface-mask" : "255.255.255.0"},      {"interface-name" : "interface-2","interface-ip" : "10.0.0.2","interface-mask" : "255.255.255.0"}]}' http://localhost:8080/wm/routing/gateway/mininet-gateway-1

#echo 'add switch to gateway curl -s http://localhost:8080/wm/routing/gateway'
#curl -X POST -d '{"gateway-name":"mininet-gateway-1", "gateway-ip" : "127.0.0.1", "switches": [{"dpid": "1"}]}' http://localhost:8080/wm/routing/gateway/mininet-gateway-1/