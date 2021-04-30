
##########################################################################################################################
												ANOTAÇÕES
##########################################################################################################################
	Algoritmos multipath encontrados:

	1. ECMP

	2. Hedera
		https://github.com/vishalshubham/Multipath-Hedera-system-in-Floodlight-controller/tree
		/5e71970f4025201f6670bbe8bd56f76f4b30e062/src/main/java/net/floodlightcontroller/hedera

		https://github.com/strategist333/hedera

	3. Olimps
		https://github.com/IstanbulBoy/floodlight-olimps

	4. MPTCP - floodlight
		https://github.com/zsavvas/MPTCP-aware-SDN


##########################################################################################################################
												CONFIG
##########################################################################################################################

	sudo apt install snap #gerenciador de pacotes

	sudo apt-get install xorg
	sudo apt-get install openbox
	sudo reboot
	xrandr --output DP-2-1 --mode 2560x1440


##########################################################################################################################
										INSTALER E COMPILAR O FLOODLIGHT
##########################################################################################################################

	
	1.  Instalar o Java 8
		$ sudo add-apt-repository ppa:openjdk-r/ppa
		$ sudo apt-get update
		$ sudo apt-get install openjdk-8-jdk
														# $ sudo apt install openjdk-11-jre
		$ sudo update-alternatives --config java   		# (escolha o "/usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java")
		$ sudo update-alternatives --config javac  		# (escolha o "/usr/lib/jvm/java-8-openjdk-amd64/bin/javac")

	2. Instalar pacotes essenciais (atual)
		$ sudo apt-get install build-essential ant maven python-dev eclipse

	3. Clonar o repositório do git (master)
		$ git clone git://github.com/floodlight/floodlight.git
		$ cd floodlight
		$ git pull origin master						# Caso esteja utilizando uma versão desatualizada
		$ git submodule init
		$ git submodule update 							# (baixa a nova interface UI)

		$ sudo chmod 777 .

		$ sudo mkdir /var/lib/floodlight
		$ sudo chmod 777 /var/lib/floodlight

	4 Compilar o floodlight com o Maven
		# entrar na pasta que tem o pom.xml
		$ mvn package -DskipTests



	Comandos curl para o statcetrypush

	5. Inserir fluxo estático
		curl -X POST -d '{"switch":"00:00:00:00:00:00:00:01", "name":"flow-mod-1", "cookie":"0", "priority":"32768",
		 "in_port":"1","active":"true", "actions":"output=2"}' http://192.168.1.215:8080/wm/staticentrypusher/json

	6.	Get flow from switch 1
		curl http://192.168.1.215:8080/wm/staticentrypusher/list/00:00:00:00:00:00:00:01/json

	7. Get flows from all switchs
		curl http://192.168.1.215:8080/wm/staticentrypusher/list/all/json

	8. Del flow
		curl -X DELETE -d '{"name":"flow-mod-1"}' http://192.168.1.215:8080/wm/staticentrypusher/json

	9. Clear switch 1
		curl http://192.168.1.215:8080/wm/staticentrypusher/clear/00:00:00:00:00:00:00:01/json
		
	10. Clear all switchs
		curl http://192.168.1.215:8080/wm/staticentrypusher/clear/all/json

	
	UTIL.curl

	1. get switches
		curl http://localhost:8080/wm/core/switch/00:00:00:00:00:00:00:01/flow/json | python -mjson.tool

##########################################################################################################################
										CRIANDO UM AMBIENTE SIMPLES O MININET
##########################################################################################################################

	$ sudo mn --controller=remote,ip=192.168.1.215,port=6653		# Cria uma rede simples controlada pelo floodlight
	

	$ sudo mn --topo=tree,2 --controller=remote,ip=192.168.1.215,port=6653 --switch=ovsk,protocols=OpenFlow13

	# Cria uma topologia 
																	
	h1 --- s1 --- s2 --- s3 --- h4
			|			  |
			h2 			  h3



	
	curl -s -d '{"switch": "00:00:00:00:00:00:00:01", "name":"00:00:00:00:00:00:00:01.5Mbps02-04.farp", 
	"ether-type":"0x806", "cookie":"0", "priority":"2", "ingress-port":"1","active":"true",
	 "actions":"output=2"}' http://192.168.1.215:8080/wm/staticflowentrypusher/json


	$ mininet>  pingall												# Para que o controlador tenha conhecimento dos host 

	$ mininet>	h2 iperf -s &										# Cria um servidor TCP em h2

	$ mininet>	h1 iperf -c h2 										# Cira um cliente TCP em h1 consultando h2 (Pega a vazão 																	
																		maxima entre h1 e h2)




##########################################################################################################################
												TUTORIAL MININET
##########################################################################################################################





##########################################################################################################################
												OPENFLOW FORWARD ALGORITHM
##########################################################################################################################

