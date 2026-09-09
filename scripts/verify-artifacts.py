#!/usr/bin/env python3
"""Check the deployed archives, not only Maven's dependency model."""
from pathlib import Path
from xml.etree import ElementTree
from zipfile import ZipFile
import json
root = Path(__file__).resolve().parent.parent
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
pom = ElementTree.parse(root / 'pom.xml').getroot()
version = pom.find('m:version', ns).text
tomcat = pom.find('m:properties/m:tomcat.version', ns).text
results = {}
for module in ['order-service', 'inventory-service', 'payment-service', 'shipping-service', 'order-query-service', 'provider-simulator']:
    with ZipFile(root / module / 'target' / f'{module}-{version}.jar') as jar:
        actual = [name for name in jar.namelist() if name.startswith('BOOT-INF/lib/tomcat-embed-core-')]
        assert actual == [f'BOOT-INF/lib/tomcat-embed-core-{tomcat}.jar'], (module, actual)
        results[module] = tomcat
print(json.dumps(results, indent=2))
