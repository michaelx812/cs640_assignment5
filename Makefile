target: src/
	find -name "*.java" > sources.txt
	javac @sources.txt

run:
	cd src;java edu.wisc.cs.sdn.simpledns.SimpleDNS -r a.root-servers.net  -e ../ec2.csv 

