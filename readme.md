I don't always write reactive applications, but when I do, it runs on Raspberry Pi
========================================

The slides of the presentation are here :

* https://github.com/mathieuancelin/reactive-raspberry-pi-cluster/raw/master/ReactiveAppOnRaspberryPi-nantes.pdf
* https://github.com/mathieuancelin/reactive-raspberry-pi-cluster/raw/master/ReactiveAppOnRaspberryPi-poitiers.pdf

or here :

* https://speakerdeck.com/mathieuancelin/i-dont-always-write-reactive-applications-but-when-i-do-it-runs-on-raspberry-pi
* https://speakerdeck.com/mathieuancelin/i-dont-always-write-reactive-applications-but-when-i-do-it-runs-on-raspberry-pi-poitiers


The video of the presentation (in french) is available here :

http://www.youtube.com/watch?v=e8fBFsEXZ5E

The applications are located in the apps folder.
You'll need to install and run local Nginx, Cassandra and Elasticsearch instances to make it work.

Basic configuration files are available in the prod-config folder.

The code is not the code actually used on the Raspberry Cluster, it a stable version without some features that are not finished yet.

 * Client lib with monitoring (does not use the new lib yet)
 * Metrics everywhere