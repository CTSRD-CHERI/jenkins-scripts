
def buildProjectWithCheribuild(String projectName, String extraArgs, String targetCPU) {
    def sdkCPU = targetCPU
    if (sdkCPU.startsWith("hybrid-")) {
        sdkCPU = sdkCPU.substring("hybrid-".length())
    }
    echo "Target CPU: ${targetCPU}, SDK CPU: ${sdkCPU}"
    // build steps that should happen on all nodes go here
    def sdkImage = docker.image("ctsrd/cheri-sdk-${sdkCPU}:latest")
    sdkImage.pull() // make sure we have the latest available from Docker Hub
    stage("build ${targetCPU}") {
        dir(projectName) {
            checkout scm
        }
        dir ('cheribuild') {
            git 'https://github.com/CTSRD-CHERI/cheribuild.git'
        }
        sdkImage.inside('-u 0') {
            env.CPU = targetCPU
            ansiColor('xterm') {
                sh '''
                    env
                    pwd
                   '''
                sh "./cheribuild/jenkins-cheri-build.py --tarball ${projectName} --cpu ${targetCPU} ${extraArgs}"
            }
        }
        sh 'ls -la'
        archiveArtifacts allowEmptyArchive: false, artifacts: "${projectName}-${targetCPU}.tar.xz", fingerprint: true, onlyIfSuccessful: true
    }
}

// This is what get's called from jenkins
def call(String name, String extraArgs='', targets=['cheri256', 'cheri128', 'mips', 'hybrid-cheri128']) {
    return targets.collectEntries {
        [(it): {
            node('docker') {
                echo "Building for ${it}"
                buildProjectWithCheribuild(name, extraArgs, it)
            }
        }]
    }
}
