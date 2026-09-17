"""Copy into /app or run with PYTHONPATH=/app inside the ERP container."""
import getpass
import secrets
from server import connect,password_hash
password=getpass.getpass('New ERP admin password (16+ characters): ')
if len(password)<16:raise SystemExit('Password too short')
if getpass.getpass('Confirm: ')!=password:raise SystemExit('Passwords do not match')
salt=secrets.token_hex(16)
with connect() as db:
    db.execute('UPDATE users SET salt=?,password=? WHERE name=?',(salt,password_hash(password,salt),'admin'))
    db.execute("DELETE FROM sessions WHERE workspace='owner'")
print('Administrator password updated; existing admin sessions revoked.')
