# WLST offline: add the sample JMS resources to an existing (stopped) domain. Run through
# scripts/wls-domain.sh --add-jms. Idempotent: skips when OrdersJMSModule already exists.
import os
from java.lang import System

domain_home = System.getenv('DOMAIN_HOME')
execfile(os.path.join(os.path.dirname(System.getenv('JMS_RESOURCES_PY')), 'jms-resources.py'))

readDomain(domain_home)
existing = ls('/JMSSystemResource', returnMap='true')
if 'OrdersJMSModule' in [str(x) for x in existing]:
    print('JMS resources already present in %s' % domain_home)
else:
    add_jms_resources()
    updateDomain()
closeDomain()
exit()
