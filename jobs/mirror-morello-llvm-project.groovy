pipeline {
    agent {
        label 'linux'
    }
    options {
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
    }
    triggers {
        cron('@hourly')
    }
    stages {
        stage("Clone from origin") {
            steps {
                cleanWs()
                sh label: "Clone", script: "git clone --mirror --reference /var/tmp/git-reference-repos/morello-llvm-project https://git.morello-project.org/morello/llvm-project.git morello-llvm-project"
            }
        }
        stage("Push to mirror") {
            steps {
                dir("morello-llvm-project") {
                    sh label: "Configure credentials", script: "git config credential.helper '!f() { echo \"username=\$GIT_USERNAME\"; echo \"password=\$GIT_PASSWORD\"; };f'"
                    withCredentials([usernamePassword(credentialsId: 'ctsrd-jenkins-new-github-api-key',
                                                      usernameVariable: 'GIT_USERNAME',
                                                      passwordVariable: 'GIT_PASSWORD')]) {
                        sh label: "Push", script: "git push --mirror https://github.com/CTSRD-CHERI/morello-llvm-project.git"
                    }
                }
            }
        }
    }
}
