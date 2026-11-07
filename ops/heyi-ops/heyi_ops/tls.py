"""Verify the certificate actually served after a serialized Nginx reload."""
import hashlib
import socket
import ssl
import time
from pathlib import Path
from .host import command, SERVICES
from .state import atomic


def observe(config):
    pem=Path(config['certificate']).read_text().split('-----END CERTIFICATE-----',1)[0]+'-----END CERTIFICATE-----'
    expected=hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest()
    context=ssl.create_default_context()
    with socket.create_connection(('127.0.0.1',443),timeout=5) as connection:
        with context.wrap_socket(connection,server_hostname=config['domain']) as secure:
            actual=hashlib.sha256(secure.getpeercert(binary_form=True)).hexdigest()
            expires=ssl.cert_time_to_seconds(secure.getpeercert()['notAfter'])
    return {'fileSha256':expected,'servedSha256':actual,'matches':expected==actual,'expiresAt':expires}


def reload(config,store):
    with store.exclusive():
        command('docker','exec',SERVICES['frontend'],'nginx','-t',timeout=10)
        command('docker','exec',SERVICES['frontend'],'nginx','-s','reload',timeout=10)
        for _ in range(10):
            value=observe(config)
            if value['matches']:
                receipt={'result':'passed','observedAt':int(time.time()),**value}
                atomic(store.root/'tls.json',receipt);return receipt
            time.sleep(1)
        raise RuntimeError('Nginx did not serve the renewed certificate')
