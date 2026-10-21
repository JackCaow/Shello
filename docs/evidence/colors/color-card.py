import sys,time
out='\x1b[2J\x1b[H\x1b[?25l\x1b[0mCOLOR RENDER CHECK\r\n\r\n'
out+='Printed spaces + erased tail must match\r\n'
for label,code in [('RGB blue','48;2;18;52;86'),('256 blue','48;5;24'),('ANSI red','41'),('Bright blue','104')]:
    out+='\x1b[0m'+label+'\r\n\x1b['+code+'m    \x1b[K\x1b[0m\r\n'
out+='\r\n256-color gradient\r\n'
for n in range(16,52):out+='\x1b[48;5;'+str(n)+'m '
out+='\x1b[0m\r\n\r\nNormal / \x1b[7mInverse\x1b[27m / Normal\r\n'
sys.stdout.write(out);sys.stdout.flush()
time.sleep(600)
