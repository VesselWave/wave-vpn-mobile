// SPDX-License-Identifier: GPL-3.0-or-later
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/stat.h>
#include <net/if.h>
#include <linux/if_packet.h>
#include <linux/if_ether.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
static volatile sig_atomic_t running=1;
static void stop(int sig){(void)sig;running=0;}
int main(int argc,char **argv){
 if(argc!=4){fprintf(stderr,"Usage: capture INTERFACE FILE SECONDS\n");return 2;}
 int fd=socket(AF_PACKET,SOCK_RAW,htons(ETH_P_ALL));if(fd<0){perror("socket");return 1;}
 unsigned int index=if_nametoindex(argv[1]);if(!index){perror("interface");return 1;}
 struct sockaddr_ll addr={.sll_family=AF_PACKET,.sll_protocol=htons(ETH_P_ALL),.sll_ifindex=(int)index};
 if(bind(fd,(struct sockaddr*)&addr,sizeof(addr))<0){perror("bind");return 1;}
 struct timeval timeout={.tv_sec=1};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
 umask(077);
 FILE *f=fopen(argv[2],"wb");if(!f){perror("file");return 1;}
 uint32_t global[]={0xa1b2c3d4,0x00040002,0,0,65535,1};fwrite(global,sizeof(global),1,f);fflush(f);
 signal(SIGTERM,stop);signal(SIGINT,stop);signal(SIGALRM,stop);alarm((unsigned)atoi(argv[3]));
 unsigned char bytes[65535];unsigned long count=0;
 while(running){ssize_t n=recv(fd,bytes,sizeof(bytes),0);if(n<0){if(errno==EINTR||errno==EAGAIN)continue;perror("recv");break;}
  struct timeval now;gettimeofday(&now,0);uint32_t record[]={(uint32_t)now.tv_sec,(uint32_t)now.tv_usec,(uint32_t)n,(uint32_t)n};
  fwrite(record,sizeof(record),1,f);fwrite(bytes,(size_t)n,1,f);fflush(f);count++;
 }
 fclose(f);close(fd);fprintf(stderr,"Captured %lu packets\n",count);return 0;
}
