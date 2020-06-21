import com.cloudbees.groovy.cps.NonCPS
import hudson.model.JobProperty
import groovy.json.JsonBuilder
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject

//def isMultiBranchPipeline() {
//    return Jenkins.get().getItem(currentBuild.projectName) instanceof WorkflowMultiBranchProject
//}

// The properties() function **sets** job propertiesorg.jenkinsci.plugins.workflow.cps.RunWrapperBinder, deleting all previously set ones!
// See e.g. https://issues.jenkins-ci.org/browse/JENKINS-44848
// Hopefully this workaround works
@NonCPS
def call(List<JobProperty<?>> args) {
    hudson.model.Job<?, ?> job = currentBuild.rawBuild.parent;
    echo("Properties before:")
    job.getAllProperties().eachWithIndex{ JobProperty<?> entry, int i ->
        echo("Property ${i}: ${entry.toString()}")
    }
    echo("Properties before:")
    for (JobProperty<?> prop : args) {
        job.addProperty(prop);
    }
    echo("Properties after:")
    job.getAllProperties().eachWithIndex{ JobProperty<?> entry, int i ->
        echo("Property ${i}: ${entry.toString()}")
    }
}
