# tkt (That Kotlin Thing)

tkt provides a way to view your AWS estate across multiple regions and accounts in one handy place. This helps you quickly and 
easily identify outsized or unused resources.

- Listing of EC2, RDS and ELB instances, EBS volumes, Elasticaches and Route53 DNS entries across all accounts and availability zones
- Used and unused reservations
- Web API for finding instances linked to an ELB - useful for shell scripts that need to ping specific instances
- SSH Config for all AWS servers

tkt was built as a custom solution for our own AWS estate, so may contain some features that are quite specific to our
usage. 

## Building

The project uses [gradle](https://gradle.org) as its build tool, is written in [Kotlin](https://kotlinlang.org), and uses 
the [Spark](http://sparkjava.com) web framework.  If you want to work on it, I highly recommend using 
[IntelliJ IDEA](https://www.jetbrains.com/idea) with the Kotlin plugin.

Run `./gradlew build` to build a jar. To start the server, run `./gradlew run` and navigate to http://localhost:4567

(hint: `alias gr=./gradlew` will make your life nicer)

If you want to run the app directly from the IDE, simply run the com.revbingo.web.ApplicationKt class.  By default it will 
use the profiles in your ~/.aws/credentials file, you can use the `--creds` flag on the command line to use a different
file e.g. for debugging purposes.

## Deploying

Run `./gradelw publish` to build and push the container. To deploy the service, run the /root/startTkt.sh script on
ukwsutil01.  The Docker container requires a volume to be mounted to /opt/tkt/db for the database, and a single file
mounted to /opt/tkt/credentials containing the credentials file.  This file should be in the format of a standard 
.aws/credentials file.

## Command line options

When starting the application from the command line, the following options are available

*  `--creds` - Path to the credentials file.  Defaults to using ~/.aws/credentials
* `-i <file>` - Location of data file containing pricing information
* `--db <db>` - Location of H2 database files
* `--no-advisor` - Disables fetching of Trusted Advisor checks
