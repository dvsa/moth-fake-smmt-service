@Liblary('PipelineUtils')
import dvsa.aws.mot.jenkins.pipeline.common.CommonFunctions

def commonFunctionsFactory = new CommonFunctions()

AWS_REGION = 'eu-west-1'
TF_LOG_LEVEL = 'ERROR'
ENV = 'int'
TF_PROJECT = 'vehicle-recalls'
BUCKET_PREFIX = 'uk.gov.dvsa.vehicle-recalls.'
BRANCH = params.BRANCH

String jenkinsctrl_node_label = 'ctrl'
String account = 'dev'

def sh_output(String script) {
  return sh(
    script: script,
    returnStdout: true
  ).trim()
}

def sh_status(String script) {
  return sh(
    script: script,
    returnStatus: true
  )
}

def log_info(String info) {
  echo "[INFO] ${info}"
}

def bucket_exists(String bucket) {
  return sh_status("aws s3 ls s3://${bucket} --region ${AWS_REGION} 2>&1 | grep -q -e \'NoSuchBucket\' -e \'AccessDenied\'")
}

def verify_or_create_bucket(String bucket_prefix, String tf_component) {
  bucket = bucket_prefix + ENV
  System.exit(0)
  if (bucket_exists(bucket) == 1) {
    log_info("Bucket ${bucket} found")
  } else {
    log_info("Bucket ${bucket} not found.")
    log_info("Creating Bucket")

    node(jenkinsctrl_node_label&&account) {
      fetch_infrastructure_code()

      extra_args = "-var environment=${ENV} " +
        "-var bucket_prefix=${bucket_prefix}"

      tf_scaffold('apply', tf_component, extra_args)
    }
  }
}

def build_and_upload_js(bucket) {
  dir("app") {
    sh("npm install")
    sh("npm run build")

    dir("dist") {
      sh("ls -lah")

      def dist_files = sh_output("ls | wc -l").toInteger()
      log_info("$dist_files files in dist")

      if (dist_files == 0) {
        abort_build("Dist file not found, aborting")
      }

      if (dist_files > 1) {
        abort_build("More than one file in dist, aborting")
      }

      dist_file = sh_output("ls")
      copy_file_to_s3(dist_file, bucket)
    }
  }

  return dist_file
}

def abort_build(String message) {
  currentBuild.result = 'ABORTED'
  error(message)
}

def copy_file_to_s3(file, bucket) {
  sh("aws s3 cp $file s3://$bucket")
}

def checkout_github_repo(String group, String repo, String branch) {
  checkout poll: false, scm: [$class: 'GitSCM', branches: [[name: branch]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', depth: 0, noTags: true, reference: '', shallow: true], [$class: 'RelativeTargetDirectory', relativeTargetDir: repo]], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/' + group + '/' + repo + '.git']]]
}

def checkout_github_repo_branch_or_master(group, repo, branch) {
  try {
    checkout_github_repo(group, repo, branch)
  } catch(error) {
    log_info("${error}")
    checkout_github_repo(group, repo, "master")
  }
}

def checkout_gitlab_repo(String group, String repo, String branch) {
  log_info("Checkout GITLAB repo \"${group}/${repo}\" branch \"${branch}")

  creds = '313a82d3-f2e7-4787-837e-7517f3ce84eb'
  checkout poll: false, scm: [$class: 'GitSCM', branches: [[name: branch]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', depth: 0, noTags: true, reference: '', shallow: true], [$class: 'RelativeTargetDirectory', relativeTargetDir: repo]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: creds, url: 'git@gitlab.motdev.org.uk:' + group + '/' + repo + '.git']]]
}

def checkout_gitlab_repo_branch_or_master(group, repo, branch) {
  try {
    checkout_gitlab_repo(group, repo, branch)
  } catch(error) {
    log_info("${error}")
    checkout_gitlab_repo(group, repo, "master")
  }
}

def tf_scaffold(action, component, extra_args, returnStdout=false) {
  log_info("Terraform ${action} on \"${component}\" with \"${extra_args}\"")

  env_type = 'FB'

  withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: env_type + '_AWS_CREDENTIALS', usernameVariable: 'aws_key_id', passwordVariable: 'aws_secret_key']]) {
    withEnv([
      "AWS_DEFAULT_REGION=${AWS_REGION}",
      "AWS_ACCESS_KEY_ID=${env.aws_key_id}",
      "AWS_SECRET_ACCESS_KEY=${env.aws_secret_key}",
      'PATH+=/opt/tfenv/bin',
      "TF_LOG=${TF_LOG_LEVEL}"
    ]) {
      return sh(
        script: """
        export TFENV_DEBUG=0
        bash -x recalls-infrastructure/bin/terraform.sh \
          --action ${action} \
          --project ${TF_PROJECT} \
          --environment ${ENV} \
          --component ${component} \
          --region ${AWS_REGION} \
          -- ${extra_args}
        """,
        returnStdout: returnStdout
      )
    }
  }
}

def tf_output(variable, tf_component) {
  //returning last line of terraform scaffold output
  return tf_scaffold("output ${variable}", tf_component, "", true).split("\n")[-1]
}

def populate_tfvars(key, value) {
  log_info("Populating tfvars \"${key}\" with \"${value}\"")

  tfvars_path = "env_${AWS_REGION}_${ENV}.tfvars"

  sh("sed -i 's|%${key}%|${value}|g' recalls-infrastructure/etc/${tfvars_path}")
}

def fetch_infrastructure_code() {
  checkout_gitlab_repo_branch_or_master('vehicle-recalls', 'recalls-infrastructure', "${BRANCH}")
  sh("ls -lah")
}

def get_tfenv() {
  if (!fileExists('/opt/tfenv/bin/tfenv')) {
    dir('/opt') { check_out_github_repo('cartest', 'tfenv', 'master') }
  } else {
    log_info('TFENV already installed. Skipping...')
  }
}

def build_and_deploy_lambda(params) {
  String name = params.name
  String repo = params.repo
  String tf_component = params.tf_component
  String code_branch = params.code_branch
  String bucket_prefix = params.bucket_prefix
  String bucket = bucket_prefix + ENV
  tfvars = params.tfvars
  dist = ''

  stage('Build ' + name) {
    sh("rm -rf \"${repo}\"")

    checkout_github_repo_branch_or_master("dvsa", repo, code_branch)
    dir(repo) {
      dist = build_and_upload_js(bucket)
    }
  }

  stage('TF Plan & Apply ' + name) {
    node('ctrl' && 'dev') {
      get_tfenv()
      fetch_infrastructure_code()

      if(tfvars) {
        tfvars.each { entry ->
          populate_tfvars(entry.key, entry.value)
        }
      }
      populate_tfvars("lambda_s3_key", dist)

      tf_scaffold('plan', tf_component, "")
      tf_scaffold('apply', tf_component, "")

      return tf_output('api_gateway_url', tf_component)
    }
  }
}

node('builder') {
  wrap([$class: 'BuildUser']) {

    wrap([$class: 'TimestamperBuildWrapper']) {
      wrap([$class: 'AnsiColorBuildWrapper', colorMapName: 'xterm']) {
          log_info("Building branch \"${BRANCH}\"")

          stage('Verify S3 Bucket') {
            verify_or_create_bucket(BUCKET_PREFIX, 's3')
          }

          fake_smmt_url = build_and_deploy_lambda(
            name: 'Fake SMMT',
            bucket_prefix: BUCKET_PREFIX,
            repo: 'vehicle-recalls-fake-smmt-service',
            tf_component: 'fake_smmt',
            code_branch: BRANCH
          )

          build_and_deploy_lambda(
            name: 'Vehicle Recalls',
            bucket_prefix: BUCKET_PREFIX,
            repo: 'vehicle-recalls-api',
            tf_component: 'vehicle_recalls_api',
            code_branch: BRANCH,
            tfvars: [
              "fake_smmt_url": fake_smmt_url
            ]
          )
        }
      }
    }
  }
