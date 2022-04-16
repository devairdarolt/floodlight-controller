<h2>REPOSITÓRIO PESSOAL PARA ESTUDO DO CONTROLADOR FLOODLIGHT</h2>



Algoritmos multipath encontrados:

1. ECMP

2. Hedera 
	
	>[Git 1](https://github.com/vishalshubham/Multipath-Hedera-system-in-Floodlight-controller/tree/5e71970f4025201f6670bbe8bd56f76f4b30e062/src/main/java/net/floodlightcontroller/hedera)
	
	>[Git 2](https://github.com/strategist333/hedera)

3. Olimps
			
	>[Git 1](https://github.com/IstanbulBoy/floodlight-olimps)

4. MPTCP - floodlight
			
	>[Git 1](https://github.com/zsavvas/MPTCP-aware-SDN)


<h3> CONFIG </h3>


>$sudo apt install snap #gerenciador de pacotes

>$sudo apt-get install xorg

>$sudo apt-get install openbox

>$sudo reboot

>$xrandr --output DP-2-1 --mode 2560x1440


<h3>INSTALER E COMPILAR O FLOODLIGHT</h3>

	
1.  Instalar o Java 8

>$ sudo add-apt-repository ppa:openjdk-r/ppa

>$ sudo apt-get update

>$ sudo apt-get install openjdk-8-jdk

>$ sudo update-alternatives --config java     # (escolha o "/usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java")

>$ sudo update-alternatives --config javac    # (escolha o "/usr/lib/jvm/java-8-openjdk-amd64/bin/javac")

2. Instalar pacotes essenciais (atual)
		
>$ sudo apt-get install build-essential ant maven python-dev eclipse

3. Clonar o repositório do git (master)
	
><b>Repositório original</b>
>$ git clone git://github.com/floodlight/floodlight.git

>$ cd floodlight                               

>$ git pull origin master                     # Caso esteja utilizando uma versão desatualizada

>$ git submodule init

>$ git submodule update                       # (baixa a nova interface UI)


>$ sudo chmod 777 .

>$ sudo mkdir /var/lib/floodlight

>$ sudo chmod 777 /var/lib/floodlight

4 Compilar o floodlight com o Maven (entrar na pasta que tem o pom.xml)
	

>$ mvn package -DskipTests


<h3>Comandos curl para o statcetrypush</h3>

5. Inserir fluxo estático

>curl -X POST -d '{"switch":"00:00:00:00:00:00:00:01", "name":"flow-mod-1", "cookie":"0", "priority":"32768","in_port":"1","active":"true", "actions":"output=2"}' http://192.168.1.215:8080/wm/staticentrypusher/json

6.	Get flow from switch 1
	
>curl http://192.168.1.215:8080/wm/staticentrypusher/list/00:00:00:00:00:00:00:01/json

7. Get flows from all switchs
		
>curl http://192.168.1.215:8080/wm/staticentrypusher/list/all/json

8. Del flow
		
>curl -X DELETE -d '{"name":"flow-mod-1"}' http://192.168.1.215:8080/wm/staticentrypusher/json

9. Clear switch 1
		
>curl http://192.168.1.215:8080/wm/staticentrypusher/clear/00:00:00:00:00:00:00:01/json
		
10. Clear all switchs
		
>curl http://192.168.1.215:8080/wm/staticentrypusher/clear/all/json
	

11. get switches
		
>curl http://localhost:8080/wm/core/switch/00:00:00:00:00:00:00:01/flow/json | python -mjson.tool

12. get packet in hyistory (tutorial.two)

		curl -s http://localhost:8080/wm/pktinhistory/history/json | python -mjson.tool

<h2>CRIANDO UM AMBIENTE SIMPLES O MININET</h2>

Cria uma rede simples controlada pelo floodlight

>$ sudo mn --controller=remote,ip=192.168.1.215,port=6653 		

>		# Cria uma topologia 
>
>		h1 --- s1 --- s2 --- s3 --- h5
>		       |       |      |
>		       h2      h3     h4

Cria uma rede em forma de arvore com 2 nós por switch de arestas

>$ sudo mn --topo=tree,2 --controller=remote,ip=192.168.1.215,port=6653 --switch=ovsk,protocols=OpenFlow13


Comando para inserir uma regra usando o statc push

>curl -s -d '{"switch": "00:00:00:00:00:00:00:01", "name":"00:00:00:00:00:00:00:01.5Mbps02-04.farp","ether-type":"0x806", "cookie":"0", "priority":"2", "ingress-port":"1","active":"true","actions":"output=2"}' http://192.168.1.215:8080/wm/staticflowentrypusher/json


Para que o controlador tenha conhecimento dos host 

>$ mininet>  pingall												

1. Cria um servidor TCP em h2 e 2. Cria um cliente TCP em h1 (O resultado é a vazão maxima da rede)


>$ mininet>	h2 iperf -s &										
>$ mininet>	h1 iperf -c h2 																											

<h3>TUTORIAL MININET</h3>												

Mostra os fluxos do switch s1
>mininet\>sh ovs-ofctl dump-flows s2 -O OpenFlow13

>ovs-vsctl dump-flows s1

Print match flow from s1
>dump-flows SWITCH FLOW

Apaga os fluxos
>ovs-ofctl del-flows s1

Lista os qos existentes
>ovs-vsctl list qos

Remove a tabela QoS
>sudo ovs-vsctl --all destroy QoS

Remover todas as Queue
>ovs-vsctl -- --all destroy Queue

Remover Queue através do id
>ovs-vsctl  destroy Queue uuid1 uuid2



# Criando Queues para usar com o floodlight
>ovs-vsctl -- set Port s1-eth1 qos=@newqos --                                            # Cria um qos para s1-eth1
>--id=@newqos create QoS type=linux-htb other-config:max-rate=1000000000 queues=0=@q0 -- # Configura limite maximo para 1 GBs
>--id=@q0 create Queue other-config:min-rate=4000000 other-config:max-rate=4000000		 # Cria uma queue de 4 Mbs --- Tudo que for colocado nessa lista terá a largura de banda limitada a 4Mbs

#Exemplo
s1   (Total:1GB, q0:100Mb, q1:5Mb)
>ovs-vsctl -- set Port s1-eth1 qos=@newqos -- set Port s1-eth2 qos=@newqos -- --id=@newqos   create   QoS    type=linux-htb    other-config:max-rate=1000000000 queues=0=@q0,1=@q1 -- --id=@q0   create   Queue   other-config:min-rate=100000000 other-config:max-rate=100000000 -- --id=@q1 create Queue other-config:min-rate=5000000


<h3>OPENFLOW FORWARD ALGORITHM</h3>


