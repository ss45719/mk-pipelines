/**
 *
 * Add Ceph OSD node to existing cluster using upmap mechanism
 *
 * Requred parameters:
 *  SALT_MASTER_URL             URL of Salt master
 *  SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *  HOST                        OSD Host (minion id) to be added
 *
 */

salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()
def ceph = new com.mirantis.mk.Ceph()
orchestrate = new com.mirantis.mk.Orchestrate()
pepperEnv = "pepperEnv"
def flags = CLUSTER_FLAGS ? CLUSTER_FLAGS.tokenize(',') : []

def runCephCommand(cmd) {
    return salt.cmdRun(pepperEnv, "I@ceph:mon and I@ceph:common:keyring:admin", cmd, checkResponse = true, batch = null, output = false)
}

def getpgmap() {
    return runCephCommand('ceph pg ls remapped --format=json')['return'][0].values()[0]
}

def generatemapping(master,pgmap,map) {
    def pg_new
    def pg_old
    for (pg in pgmap) {
        pg_new = pg["up"].minus(pg["acting"])
        pg_old = pg["acting"].minus(pg["up"])
        for (i = 0; i < pg_new.size(); i++) {
            def string = "ceph osd pg-upmap-items " + pg["pgid"].toString() + " " + pg_new[i] + " " + pg_old[i] + ";"
            map.add(string)
        }
    }
}

timeout(time: 12, unit: 'HOURS') {
    node("python") {
        try {
            // create connection to salt master
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

            if (!HOST.toLowerCase().contains("osd")) {
                common.errorMsg("This pipeline can only be used to add new OSD nodes to an existing Ceph cluster.")
                throw new InterruptedException()
            }

            stage ("verify client versions")
            {
                // I@docker:swarm and I@prometheus:server - mon* nodes
                def nodes = salt.getMinions(pepperEnv, "I@ceph:common and not ( I@docker:swarm and I@prometheus:server )")
                for ( node in nodes )
                {
                    def versions = salt.cmdRun(pepperEnv, node, "ceph features --format json", checkResponse=true, batch=null, output=false).values()[0]
                    versions = new groovy.json.JsonSlurperClassic().parseText(versions[0][node])
                    if ( versions['client']['group']['release'] != 'luminous' )
                    {
                        throw new Exception("client installed on " + node + " is not luminous. Update all clients to luminous before using this pipeline")
                    }
                }
            }

            stage("enable luminous compat") {
                runCephCommand('ceph osd set-require-min-compat-client luminous')['return'][0].values()[0]
            }

            stage("enable upmap balancer") {
                runCephCommand('ceph balancer on')['return'][0].values()[0]
                runCephCommand('ceph balancer mode upmap')['return'][0].values()[0]
            }

            stage("set norebalance") {
                runCephCommand('ceph osd set norebalance')['return'][0].values()[0]
            }

            stage('Install infra') {
                orchestrate.installFoundationInfraOnTarget(pepperEnv, HOST)
            }

            stage('Install Ceph OSD') {
                orchestrate.installCephOsd(pepperEnv, HOST)
            }

            stage("Update/Install monitoring") {
                def prometheusNodes = salt.getMinions(pepperEnv, 'I@prometheus:server')
                if (!prometheusNodes.isEmpty()) {
                    //Collect Grains
                    salt.enforceState(pepperEnv, HOST, 'salt.minion.grains')
                    salt.runSaltProcessStep(pepperEnv, HOST, 'saltutil.refresh_modules')
                    salt.runSaltProcessStep(pepperEnv, HOST, 'mine.update')
                    sleep(5)
                    salt.enforceState(pepperEnv, HOST, ['fluentd', 'telegraf', 'prometheus'])
                    salt.enforceState(pepperEnv, 'I@prometheus:server', 'prometheus')
                } else {
                    common.infoMsg('No Prometheus nodes in cluster. Nothing to do')
                }
            }

            stage("Update host files") {
                salt.enforceState(pepperEnv, '*', 'linux.network.host')
            }

            def mapping = []

            stage("update mappings") {
                def pgmap
                for (int x = 1; x <= 3; x++) {
                    pgmap = getpgmap()
                    if (pgmap == '') {
                        return 1
                    } else {
                        pgmap = new groovy.json.JsonSlurperClassic().parseText(pgmap)
                        generatemapping(pepperEnv, pgmap, mapping)
                        mapping.each(this.&runCephCommand)
                        sleep(30)
                    }
                }
            }

            stage("unset norebalance") {
                runCephCommand('ceph osd unset norebalance')['return'][0].values()[0]
            }

            stage("wait for healthy cluster") {
                ceph.waitForHealthy(pepperEnv, "I@ceph:mon and I@ceph:common:keyring:admin", flags)
            }
        }
        catch (Throwable e) {
            // There was an error or exception thrown. Unset norebalance.
            runCephCommand('ceph osd unset norebalance')['return'][0].values()[0]
            throw e
        }
    }
}
