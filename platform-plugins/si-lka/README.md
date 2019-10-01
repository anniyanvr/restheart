# SoftInstigate License Key Activator

Extension to activate SoftInstigate commercial license keys.

The Commercial License comes into effect by executing the License Key Activation process.

As long as this process is not completed, the terms and conditions of the Affero General Public License are in force.

## Requirements

The SoftInstigate License Key Activator requires RESTHeart Pro version 4.0 and later.

If you use a previous version you should upgrade; for more information please contact support@softinstigate.com

__Note__: you temporarily need to open the port 18080 in your RESTHeart Pro server in order to allow the licensing process to work. After the commercial license has been accepted and activated, you can safely close that port.

## The activation process

The license key activation requires two steps:

- Copy and extract the provided license key archive to the RESTHeart Pro root directory;
- Execute RESTHeart Pro and accept the license agreement.

### Copy and extract the provided license key archive

Copy the license key tar archive you have been provided by SoftInstigate to the RESTHeart Pro root directory.

```bash
$ cp lickey.tar.gz <rhroot>
```

Where \<rhroot> is the RESTHeart Pro root directory.

Extract the package files.

```bash
$ cd <rhroot>
$ tar -xzf lickey.tar.gz
```

This creates the directory `lickey` with the following files:

- comm-license.key
- COMM-LICENSE.txt
- README.md

### Accept the License Agreement

Running RESTHeart Pro will cause the following warning message:

```bash

WARN  com.restheart.CommLicense - The License Agreement has not yet been accepted.
WARN  com.restheart.CommLicense - All requests are temporarily blocked.
INFO  com.restheart.CommLicense - Please open your browser at http://<ip-of-restheart>:18080 and accept License Agreement.
INFO  com.restheart.CommLicense - The  License Agreement is available at <rhroot>/lickey/COMM-LICENSE.txt
```

As a result of this error, all requests to RESTHeart are temporarily blocked.

To properly run RESTHeart you must explicitly accept the license:

 - open your browser at `http://<ip-of-restheart>:18080`.
 - read and approve the license

> To accept the License Agreement you need to select the two checkboxes and then click the "Activate the License Key" button.

When done, you will find the following messages in RESTHeart's log:

```
INFO com.restheart.CommLicense - License Agreement accepted.
INFO  com.restheart.CommLicense - Requests are enabled.
```

Once the License Agreement has been accepted, at startup RESTHeart just logs the following message confirming that the Commercial License is in force:

```
INFO  com.restheart.CommLicense - This instance of RESTHeart is licenced under the Terms and Conditions of the License Agreement available at <lk-dir>/COMM-LICENSE.txt
```

Now that the license agreement has been successfully accepted, you can close port 18080 in your server.

## Silent acceptance

You can also accept the License Agreement with the system property `ACCEPT_LICENSE_AGREEMENT`

```
java -DACCEPT_LICENSE_AGREEMENT=true -jar restheart-pro.jar
```

## Specify a license key directory

The default directory that contains the license key and the license agreement is `lickey` next to the restheart-pro.jar file:

```
- restheart-pro.jar
- lickey
    - comm-license.key
    - COMM-LICENSE.txt
```

You can set a different directory with the system property `lk-dir`:

```
java -Dlk-dir=/etc/rh-lickey -jar restheart-pro.jar
```

## Docker

If you use Docker to run RESTHeart, you need to mount the `lickey` directory.

Add the following option to the `docker run` command:

```bash
-v ~/lickey:/opt/restheart/lickey/
```