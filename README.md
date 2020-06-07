# EnderChest
A fast and concurrent update system

# Introduction
When Minecraft developers create a launcher, they often use the S-Update system. 
Unfortunately, this system is outdated and unmaintained.

While discussing with Litarvan about it,
I decided to create this system written in Scala.

**I highly recommend to use Scala, but you can use any JVM Language like Java or Kotlin.**

# Features
## Fast
This system uses the xxHash32 algorithm to generate checksums.
This operation is ~16.36x faster than md5.

## Concurrent and lightweight
EnderChest uses Scala's futures and akka streams to asynchronously process data.
The EnderChest's parallel system make data processing lightweight and reduce the memory footprint.

# Install
EnderChest (server & client) only requires Java 8+ to be installed.

## Server
Download the `enderchest-server-xxxx.jar`in the release section
You now just need to run the server in an empty directory with permissions `rwx` using `java -jar`

Start the server for the first time will generate the config.yml file and the `files` directory.
Now, you can put your files in the `files` directory, then reload/restart the server.

## Client
You just need to import the client library.

### Using a build tool
I highly recommend developers to use a dependency management system like Gradle or SBT.

<details>
<summary>Using Gradle</summary>

```gradle
repositories {
  mavenCentral()
}

dependencies {
  implementation 'io.github.iltotore:ec-client:version'
}
```
</details>

<details>
<summary>Using SBT</summary>

```sbt
libraryDependencies += "io.github.iltotore" %% "ec-client" % "version"
```
</details>

# Support
## Issues
If you experience a bug/issue using EnderChest, you can create a new github issue.

## General questions
Join us on Discord

[![JOIN ECS](https://discordapp.com/api/guilds/718109282406498415/embed.png?style=banner2)](https://discord.gg/zX3A8Nb)