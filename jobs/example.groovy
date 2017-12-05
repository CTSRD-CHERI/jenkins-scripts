for (i in ["FPU"]) {
    multibranchPipelineJob("CHERI1-${i}-TEST-pipeline") {
        branchSources {
            github {
                repoOwner 'CTSRD-CHERI'
                repository 'cheritest'
                scanCredentialsId 'ctsrd-jenkins-github-user'
                checkoutCredentialsId 'ctsrd-jenkins-github-user'
                // credentialsId 'foo'
            }
        }
        configure {
            it / 'factory' << 'scriptPath'('jenkins/CHERI1/Jenkinsfile')
            // This appears to be needed since github doesn't allow credentialsID....
            // it / 'sources'/ 'data' / 'jenkins.branch.BranchSource'/ 'source' << 'credentialsId'('ctsrd-jenkins-github-user')
        }
    }
}