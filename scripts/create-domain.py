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
h2_dir = env('H2_DIR')

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

# Embedded H2 data source for the sample apps (driver jar lives in $DOMAIN_HOME/lib).
ds_name = 'OrdersDS'
ds_path = '/JDBCSystemResource/%s/JdbcResource/%s' % (ds_name, ds_name)
cd('/')
create(ds_name, 'JDBCSystemResource')

cd(ds_path)
create(ds_name, 'JDBCDataSourceParams')
cd('JDBCDataSourceParams/NO_NAME_0')
set('JNDIName', 'jdbc/OrdersDS')
set('GlobalTransactionsProtocol', 'OnePhaseCommit')

cd(ds_path)
create(ds_name, 'JDBCDriverParams')
cd('JDBCDriverParams/NO_NAME_0')
set('DriverName', 'org.h2.Driver')
# MODE=LEGACY: EclipseLink's H2 platform still emits "BIGINT IDENTITY", which H2 2.x
# only accepts in legacy compatibility mode.
set('Url', 'jdbc:h2:file:%s/sample;MODE=LEGACY;AUTO_SERVER=FALSE' % h2_dir)
set('PasswordEncrypted', 'sa')
create(ds_name, 'Properties')
cd('Properties/NO_NAME_0')
create('user', 'Property')
cd('Property/user')
set('Value', 'sa')

cd(ds_path)
create(ds_name, 'JDBCConnectionPoolParams')
cd('JDBCConnectionPoolParams/NO_NAME_0')
set('InitialCapacity', 1)
set('MaxCapacity', 5)
set('TestTableName', 'SQL SELECT 1')

cd('/JDBCSystemResource/%s' % ds_name)
set('Target', 'AdminServer')

writeDomain(domain_home)
closeTemplate()

print('Domain written to %s' % domain_home)
exit()
