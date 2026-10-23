# WLST offline script: create the development domain used by the capture prototype.
# Run through scripts/wls-domain.sh, which supplies the environment.

from java.lang import System

def env(name):
    value = System.getenv(name)
    if value is None or value == '':
        raise ValueError('environment variable %s is not set' % name)
    return value

oracle_home = env('ORACLE_HOME')
domain_home = env('DOMAIN_HOME')
domain_name = env('DOMAIN_NAME')
java_home = env('WLS_JAVA_HOME')
admin_port = int(env('WLS_ADMIN_PORT'))
listen_address = env('WLS_LISTEN_ADDRESS')
admin_user = env('WLS_ADMIN_USER')
admin_password = env('WLS_ADMIN_PASSWORD')

print('Creating domain %s in %s' % (domain_name, domain_home))

selectTemplate('Basic WebLogic Server Domain')
loadTemplates()

setOption('DomainName', domain_name)
setOption('JavaHome', java_home)
setOption('ServerStartMode', 'dev')
setOption('OverwriteDomain', 'true')

cd('/Servers/AdminServer')
set('Name', 'AdminServer')
set('ListenPort', admin_port)
set('ListenAddress', listen_address)

# The security tree keeps the template's name even after setOption('DomainName').
cd('/Security/base_domain/User/weblogic')
set('Name', admin_user)
set('Password', admin_password)

writeDomain(domain_home)
closeTemplate()

print('Domain written to %s' % domain_home)
exit()
