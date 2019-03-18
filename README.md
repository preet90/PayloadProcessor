# DicomProcessor

Java code for processing medical device files

## Compilation

To compile you'll need JDK version 1.8 or higher and Apache Maven

https://maven.apache.org/install.html

```
mvn package
```

## Execution

Required options

```
-sa 
```
shutdownAfterMinutes An option that will be used to determine how long the engine should run and will shutdown after the specified amount of minutes
```
-rq
```
requestQueue The request queue of the table request_queue in the database that should be processed
```
-sf 
```
scriptFileLocation The location of the directory of the javascript scripts that the engine will use to process the medical device files
```
-sy 
```
synchronizeRoutine Delay of minutes when the engine should synchronize the specified scriptFileLocation with the routine_library table

After the project is built the file that has to be run is under target/appassembler/bin/

For Windows the file is called
```
app.bat
```
For Linux the file is called
```
app
```

Example of execution:
```
.\target\appassembler\bin\app.bat -sf src/main/resources/routineLibrary/ -rq dicom_queue -sa 1 -sy 1
```
