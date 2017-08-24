import groovy.json.*

class CheribuildProject implements Serializable {
    /// general/build parameters
    String projectName // the cheribuild project name
    String extraArgs // additional arguments to pass to cheribuild.py
    String cpu // --cpu flag for cheribuild
    String sdkCPU  // the SDK used to build (e.g. for cheri256-hybrid will use the cheri256 sdk to build MIPS code)
    String tarballName  // output tarball name (default is "${projectName}-${cpu}.tar.xz")
    // by default we try to do an incremental build and if that fails fall back to a full build
    // FIXME: not sure this is actually working, it seems to rebuild all the time
    boolean noIncrementalBuild = false // whether to force a clean build (i.e. don't pass --no-clean to cheribuild)

    /// Test parameters:
    def testTimeout = 120 * 60 // timeout for running tests (default 2 hours)
    boolean minimalTestImage
    String testScript  // if set this will be invoked by ./boot_cheribsd.py in the test stage. If not tests are skipped
    String testExtraArgs  // Additional command line options to be passed to ./boot_cheribsd.py
    // FIXME: implement this:
    // List testOutputs  // if set these files will be scp'd from CheriBSD after running the tests (e.g. JUnit XML files)

    /// hooks
    def beforeSCM // callback before checking out the sources
    def beforeBuildOutsideDocker  // callback before starting docker
    def beforeBuild  // first command inside docker
    def beforeTarball  // after building but before creating the tarball
    def afterBuildInDocker  // after building and tarball (no longer inside docker)
    def afterBuild  // after building and tarball (no longer inside docker)
    def beforeTestsOutsideDocker // before running the tests (inside docker)
    def beforeTests // before running the tests (inside docker)
    // def afterTestsInCheriBSD // before running the tests (sent to cheribsd command line)
    def afterTestsInDocker // before running the tests (inside docker, cheribsd no longer running)
    def afterTests // before running the tests (no longer inside docker)

    // Run a beforeXXX hook (beforeBuild, beforeTarball, etc.)
    def runCallback(cb) {
        // def cb = this."${hook}"
        if (!cb) {
            return
        }
        // echo "Running callback ${hook}"
        if ((cb instanceof Closure) || cb.metaClass.respondsTo(cb, 'call')) {
            //noinspection GroovyAssignabilityCheck
            cb(this.cpu)
        } else {
            assert cb instanceof String
            if (!cb.allWhitespace) {
                sh cb
            }
        }
    }

    def build() {
        // build steps that should happen on all nodes go here
        echo "Target CPU: ${cpu}, SDK CPU: ${sdkCPU}, output: ${tarballName}"
        def sdkImage = docker.image("ctsrd/cheri-sdk-${sdkCPU}:latest")
        sdkImage.pull() // make sure we have the latest available from Docker Hub
        runCallback(beforeSCM)
        dir(projectName) {
            checkout scm
        }
        dir('cheribuild') {
            git 'https://github.com/CTSRD-CHERI/cheribuild.git'
        }
        runCallback(beforeBuildOutsideDocker)
        sdkImage.inside('-u 0') {
            env.CPU = cpu
            ansiColor('xterm') {
                sh "rm -fv ${tarballName}; pwd"
                runCallback(beforeBuild)
                def cheribuildCmd = "./cheribuild/jenkins-cheri-build.py --build ${projectName} --cpu ${cpu} ${extraArgs}"
                // by default try an incremental build first and if that fails fall back to a clean build
                // this behaviour can be disabled by passing noIncrementalBuild: true
                if (noIncrementalBuild) {
                    sh "${cheribuildCmd}"
                } else {
                    sh "${cheribuildCmd} --no-clean || (echo 'incremental build failed!' && ${cheribuildCmd})"
                }
                runCallback(beforeTarball)
                sh "./cheribuild/jenkins-cheri-build.py --tarball --tarball-name ${tarballName} --no-build ${projectName} --cpu ${cpu} ${extraArgs}"
                runCallback(afterBuildInDocker)
            }
        }
        sh 'ls -lah'
        archiveArtifacts allowEmptyArchive: false, artifacts: tarballName, fingerprint: true, onlyIfSuccessful: true
        runCallback(afterBuild)
    }

    def runTests() {
        def imageName
        if (cpu == 'mips') {
            imageName = 'cheri256-cheribsd-mips'
        } else {
            // boot a world with a hybrid userspace (it contains all the necessary shared libs)
            // There is no need for the binaries to be CHERIABI
            imageName = "${sdkCPU}-cheribsd-hybrid"
        }
        def testImageArg = ''
        if (minimalTestImage) {
            testImageArg = "--disk-image /usr/local/share/cheribsd/cheribsd-minimal.img"
        }
        def cheribsdImage = docker.image("ctsrd/${imageName}:latest")
        cheribsdImage.pull()
        runCallback(beforeTestsOutsideDocker)
        cheribsdImage.inside('-u 0') {
            // ./boot_cheribsd.py --qemu-cmd ~/cheri/output/sdk256/bin/qemu-system-cheri --disk-image ./cheribsd-jenkins_bluehive.img.xz --kernel cheribsd-cheri-malta64-kernel.bz2 -i
            // TODO: allow booting the minimal bluehive disk-image
            def testCommand = "'export CPU=${cpu}; " + testScript.replaceAll('\'', '\\\'') + "'"
            ansiColor('xterm') {
                sh "wget https://raw.githubusercontent.com/RichardsonAlex/cheri-sdk-docker/master/cheribsd/boot_cheribsd.py -O /usr/local/bin/boot_cheribsd.py"
                runCallback(beforeTests)
                sh "boot_cheribsd.py ${testImageArg} --test-command ${testCommand} --test-archive ${tarballName} --test-timeout ${testTimeout} ${testExtraArgs}"
            }
            runCallback(afterTestsInDocker)
        }
        runCallback(afterTests)
    }

    def run() {
        if (!tarballName) {
            tarballName = "${projectName}-${cpu}.tar.xz"
        }

        assert cpu
        // compute sdkCPU from args
        sdkCPU = cpu
        if (sdkCPU.startsWith("hybrid-")) {
            sdkCPU = sdkCPU.substring("hybrid-".length())
        }

        println(new JsonBuilder( this ).toPrettyString())
        stage "Build ${cpu}", {
            build()
        }
        if (testScript) {
            stage "run tests for ${cpu}", {
                runTests()
            }
        }
        // TODO: clean up properly and remove the created artifacts?
    }
}

// This is what gets called from jenkins
def call(Map args) {
    def targets = args.get('targets', ['mips', 'cheri256', 'cheri128', 'hybrid-cheri128'])
    def name = args.name
    args.remove('targets')
    args.remove('name')
    assert name
    Map<String, Closure> jobs = targets.collectEntries {
        [(it): {
            node('docker') {
                // The map spread operator is not supported in Jenkins
                // def project = new CheribuildProject(projectName: args.name, *:args)
                def ctorArgs = args.getClass().newInstance(args)
                ctorArgs.projectName = name
                ctorArgs.cpu = it
                def project = ctorArgs as CheribuildProject
                project.run()
            }
        }]
    }
    if (args.sequential) {
        jobs.each { k, job -> job() }
    } else {
        stage("Cheribuild") {
            //noinspection GroovyAssignabilityCheck
            parallel jobs
        }
    }
}