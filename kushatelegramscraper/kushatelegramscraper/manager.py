import warnings
from telethon.sync import TelegramClient
from telethon.errors.rpcerrorlist import PhoneNumberBannedError
import pickle, pyfiglet
from colorama import init, Fore
import os, random
from time import sleep

init()

lg = Fore.LIGHTGREEN_EX
w = Fore.WHITE
cy = Fore.CYAN
ye = Fore.YELLOW
r = Fore.RED
n = Fore.RESET
colors = [lg, r, w, cy, ye]

def banner():
    f = pyfiglet.Figlet(font='slant')
    banner = f.renderText('Telegram')
    print(f'{random.choice(colors)}{banner}{n}')
    print(r+' Kusha Mühendislik'+n+'\n')


def clr():
    if os.name == 'nt':
        os.system('cls')
    else:
        os.system('clear')

while True:
    clr()
    #print(r)
    banner()
    #print(n)
    print(lg+'[1] Yeni Hesap Ekle'+n)
    print(lg+'[2] Banlı Hesapları Göster'+n)
    print(lg+'[3] Bütün Hesapları Göster'+n)
    print(lg+'[4] Bir Hesabı Sil'+n)
    #print(lg+'[5] Update your Genisys'+n)
    print(lg+'[5] Çıkış')
    a = int(input(f'\nSeçiminizi Girin: {r}'))
    if a == 1:
        with open('vars.txt', 'ab') as g:
            newly_added = []
            while True:
                a = int(input(f'\n{lg}API ID: {r}'))
                b = str(input(f'{lg}API Hash: {r}'))
                c = str(input(f'{lg}Telefon No Girin: {r}'))
                p = ''.join(c.split())
                pickle.dump([a, b, p], g)
                newly_added.append([a, b, p])
                ab = input(f'\nBaşka Hesap Eklemek İstiyor Musunuz ?[y/n]: ')
                if 'y' in ab:
                    pass
                else:
                    print('\n'+lg+'[i] Hesaplar vars.txt Kaydedildi.'+n)
                    g.close()
                    sleep(3)
                    clr()
                    print(lg + '[*] Yeni Hesaba Giriş Yapılıyor...\n')
                    for added in newly_added:
                        c = TelegramClient(f'sessions/{added[2]}', added[0], added[1])
                        try:
                            c.start()
                            print(f'n\n{lg}[+] Giriş Başarılı - {added[2]}')
                            c.disconnect()
                        except PhoneNumberBannedError:
                            print(f'{r}[!] {added[2]} Banlı! Bu Hesabı Çıkartın')
                            continue
                        print('\n')
                    input(f'\n{lg}Lütfen Ana Menüye Dönün...')
                    break
        g.close()
    elif a == 2:
        accounts = []
        banned_accs = []
        h = open('vars.txt', 'rb')
        while True:
            try:
                accounts.append(pickle.load(h))
            except EOFError:
                break
        h.close()
        if len(accounts) == 0:
            print(r+'[!] Lütfen Hesap Ekleyin')
            sleep(3)
        else:
            for account in accounts:
                api_id = int(account[0])
                api_hash = str(account[1])
                phone = str(account[2])
                client = TelegramClient(f'sessions\\{phone}', api_id, api_hash)
                client.connect()
                if not client.is_user_authorized():
                    try:
                        client.send_code_request(phone)
                        client.sign_in(phone, input('[+] Kodu Giriniz: '))
                    except PhoneNumberBannedError:
                        print(r+str(phone) + ' Banlı'+n)
                        banned_accs.append(account)
            if len(banned_accs) == 0:
                print(lg+'Güzel Haber ! Banlı Hesap Yok')
                input('\nAna Menüye Gitmek İçin Enter Tuşuna Basınız')
            else:
                for m in banned_accs:
                    accounts.remove(m)
                with open('vars.txt', 'wb') as k:
                    for a in accounts:
                        Id = a[0]
                        Hash = a[1]
                        Phone = a[2]
                        pickle.dump([Id, Hash, Phone], k)
                k.close()
                print(lg+'[i] Tüm Banlı Hesaplar Silindi'+n)
                input('\nAna Menüye Gitmek İçin Enter Tuşuna Basınız')
    elif a == 3:
        display = []
        j = open('vars.txt', 'rb')
        while True:
            try:
                display.append(pickle.load(j))
            except EOFError:
                break
        j.close()
        print(f'\n{lg}')
        print(f'API ID  |            API Hash              |    Telefon')
        print(f'==========================================================')
        i = 0
        for z in display:
            print(f'{z[0]} | {z[1]} | {z[2]}')
            i += 1
        print(f'==========================================================')
        input('\nAna Menüye Gitmek İçin Enter Tuşuna Basınız')

    elif a == 4:
        accs = []
        f = open('vars.txt', 'rb')
        while True:
            try:
                accs.append(pickle.load(f))
            except EOFError:
                break
        f.close()
        i = 0
        print(f'{lg}[i] Silinecek Hesabı Seçin\n')
        for acc in accs:
            print(f'{lg}[{i}] {acc[2]}{n}')
            i += 1
        index = int(input(f'\n{lg}[+] Seçiminizi Girin: {n}'))
        phone = str(accs[index][2])
        session_file = phone + '.session'
        if os.name == 'nt':
            os.system(f'del sessions\\{session_file}')
        else:
            os.system(f'rm sessions/{session_file}')
        del accs[index]
        f = open('vars.txt', 'wb')
        for account in accs:
            pickle.dump(account, f)
        print(f'\n{lg}[+] Hesap Silindi{n}')
        input(f'{lg}Ana Menüye Gitmek İçin Enter Tuşuna Basınız{n}')
        f.close()
    elif a == 5:
        clr()
        banner()
        quit()