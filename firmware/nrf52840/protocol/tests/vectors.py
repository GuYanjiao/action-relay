# Synthetic public vectors, generated only in CI using Python's standard crypto.
import hashlib,hmac,pathlib
key=bytes(range(16));phone=bytes(range(16,32));watch=bytes(range(32,48))
mac=lambda k,d:hmac.new(k,d,hashlib.sha256).digest()
prk=mac(phone+watch,key)
a=mac(prk,b'miwear-auth\x01');b=mac(prk,a+b'miwear-auth\x02');block=a+b
watch_proof=mac(block[:16],watch+phone)
phone_proof=mac(block[16:32],phone+watch)
arrays={'gold_keys':block[:40],'gold_watch':watch_proof,'gold_phone':phone_proof}
pathlib.Path(__file__).with_name('vectors.h').write_text(''.join(
    f'static const unsigned char {name}[]={{'+','.join(str(v) for v in data)+'};\n'
    for name,data in arrays.items()))
