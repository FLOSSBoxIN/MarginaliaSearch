# Run

When developing locally, this directory will contain run-time data required for
the search engine. In a clean check-out, it only contains the tools required to 
bootstrap this directory structure.

## Requirements
While the system is designed to run bare metal in production,
for local development, you're strongly encouraged to use docker
or podman. These are a bit of a pain to install, but if you follow
[this guide](https://docs.docker.com/engine/install/ubuntu/#install-using-the-repository) 
you're on the right track.

## Set up
To go from a clean check out of the git repo to a running search engine,
follow these steps. You're assumed to sit in the project root the whole time.

1. Run the one-time setup, it will create the
basic runtime directory structure and download some models and data that doesn't
come with the git repo because git deals poorly with large binary files.

```
$ run/setup.sh
```

2. Compile the project and build docker images

```
$ ./gradlew assemble docker
```

3. Initialize the database
```
$ docker-compose up -d mariadb
$ ./gradlew flywayMigrate
```

4. Bring the system online. We'll run it in the foreground in the terminal this time
because it's educational to see the logs. Add `-d` to run in the background.

```
$ docker-compose up
```

5. You should now be able to access the system.

| Address                 | Description      |
|-------------------------|------------------|
| https://localhost:8080/ | User-facing GUI  |
| https://localhost:8081/ | Operator's GUI   |

6. Download Sample Data

A script is available for downloading sample data. The script will download the
data from https://downloads.marginalia.nu/ and extract it to the correct location.

The system will pick the data up automatically.

```shell
$ run/download-samples l
```

Four sets are available:

| Name | Description                     |
|------|---------------------------------|
| s    | Small set, 1000 domains         |
| m    | Medium set, 2000 domains        |
| l    | Large set, 5000 domains         |
| xl   | Extra large set, 50,000 domains |

Warning: The XL set is intended to provide a large amount of data for 
setting up a pre-production environment. It may be hard to run on a smaller
machine.  It's barely runnable on a 32GB machine; and total processing time
is around 5 hours.

The 'l' set is a good compromise between size and processing time and should
work on most machines.

## Experiment Runner

The script `experiment.sh` is a launcher for the experiment runner, which is useful when 
evaluating new algorithms in processing crawl data. 