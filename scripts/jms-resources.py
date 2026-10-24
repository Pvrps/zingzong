# WLST offline: the JMS resources the sample applications use. Defines add_jms_resources(),
# called by create-domain.py on a new domain and by add-jms.py on an existing one.
#
#   jms/OrdersCF      connection factory (default targeting: every server of the domain)
#   jms/OrderEvents   queue on OrdersJMSServer (AdminServer), published by orders-ear's
#                     OrderService.confirmOrder, consumed by notify-ear's OrderEventListener

def add_jms_resources():
    cd('/')
    create('OrdersJMSServer', 'JMSServer')
    assign('JMSServer', 'OrdersJMSServer', 'Target', 'AdminServer')

    cd('/')
    create('OrdersJMSModule', 'JMSSystemResource')
    assign('JMSSystemResource', 'OrdersJMSModule', 'Target', 'AdminServer')

    cd('/JMSSystemResource/OrdersJMSModule')
    create('OrdersSub', 'SubDeployment')
    assign('JMSSystemResource.SubDeployment', 'OrdersJMSModule.OrdersSub', 'Target', 'OrdersJMSServer')

    cd('/JMSSystemResource/OrdersJMSModule/JmsResource/NO_NAME_0')
    create('OrdersCF', 'ConnectionFactory')
    cd('ConnectionFactory/OrdersCF')
    set('JNDIName', 'jms/OrdersCF')
    set('DefaultTargetingEnabled', 'true')
    # XA: the consuming MDB is transactional, and the producer sends inside confirmOrder's transaction.
    create('OrdersCF', 'TransactionParams')
    cd('TransactionParams/NO_NAME_0')
    set('XAConnectionFactoryEnabled', 'true')

    cd('/JMSSystemResource/OrdersJMSModule/JmsResource/NO_NAME_0')
    create('OrderEvents', 'Queue')
    cd('Queue/OrderEvents')
    set('JNDIName', 'jms/OrderEvents')
    set('SubDeploymentName', 'OrdersSub')
    print('JMS resources added: jms/OrdersCF, jms/OrderEvents on OrdersJMSServer')
