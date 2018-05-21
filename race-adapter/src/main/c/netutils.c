/*
 * Copyright (c) 2017, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * RACE adapter network utility functions
 */

#include <sys/types.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <fcntl.h>
#include <netdb.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <stdio.h>
#include <stdbool.h>

 /*
  * return socket fd or -1 if failure (setting err_msg)
  * service is usually just the port
  *
  * TODO - we can't free the servinfo list here because we return a field of it
  */ 
int race_client_socket (char* hostname, char* service, struct sockaddr** serveraddr, socklen_t* addrlen, const char** err_msg) {
    int sockfd = -1;
    struct addrinfo hints, *servinfo = NULL, *p;
    int rv;

    *serveraddr = NULL;
    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_UNSPEC; // IPv4 or IPv6
    hints.ai_socktype = SOCK_DGRAM;  // UDP

    //--- look up addrinfo
    if ((rv = getaddrinfo(hostname, service, &hints, &servinfo)) != 0) {
        *err_msg = gai_strerror(rv);
        return -1;
    }

    //--- loop through results and return the first we can connect to
    for (p = servinfo; p != NULL; p = p->ai_next) {
        if ((sockfd = socket(p->ai_family, p->ai_socktype,p->ai_protocol)) >= 0) {
            *serveraddr = p->ai_addr;
            *addrlen = p->ai_addrlen;
            break;
        }
    }

    if (sockfd < 0) {
        *err_msg = "no suitable host/service found";
    }
    return sockfd;
}

int race_server_socket6 (char* port, const char** err_msg) {
    int sockfd;
    if ( (sockfd = socket(AF_INET6, SOCK_DGRAM, 0)) < 0 ) {
        *err_msg = strerror(errno);
        return -1;
    }

    struct sockaddr_in6 serveraddr;
    memset( &serveraddr, 0, sizeof(serveraddr) );
    serveraddr.sin6_family = AF_INET6;
    serveraddr.sin6_port = htons( atoi(port) );
    serveraddr.sin6_addr = in6addr_any;

    if ( bind(sockfd, (struct sockaddr *)&serveraddr, sizeof(serveraddr)) < 0 ) {
        *err_msg = strerror(errno);
        return -1;
    }

    return sockfd;
}

int race_server_socket (char* port, const char** err_msg) {
    int sockfd;
    if ( (sockfd = socket(AF_INET, SOCK_DGRAM, 0)) < 0 ) {
        *err_msg = strerror(errno);
        return -1;
    }

    struct sockaddr_in serveraddr;
    memset( &serveraddr, 0, sizeof(serveraddr) );
    serveraddr.sin_family = AF_INET;
    serveraddr.sin_port = htons( atoi(port) );

    if ( bind(sockfd, (struct sockaddr *)&serveraddr, sizeof(serveraddr)) < 0 ) {
        *err_msg = strerror(errno);
        return -1;
    }

    return sockfd;
}

struct sockaddr* race_create_sockaddr6(socklen_t* socklen) {
    int len = sizeof(struct sockaddr_in6);
    struct sockaddr_in6* saddr = malloc(len);

    bzero(saddr, len);
    *socklen = len;

    return (struct sockaddr*)saddr;
}

struct sockaddr* race_create_sockaddr(socklen_t* socklen) {
    int len = sizeof(struct sockaddr_in);
    struct sockaddr_in *saddr = malloc(len);

    bzero(saddr, len);
    *socklen = len;

    return (struct sockaddr*)saddr;
}

bool race_set_nonblocking (int fd, const char** err_msg) {
    if (fcntl(fd, F_SETFL, O_NONBLOCK) < 0){
        *err_msg = strerror(errno);        
        return 0;
    } else {
        return 1;
    }
}

bool race_set_blocking (int fd, const char** err_msg) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) {
        *err_msg = strerror(errno);
        return 0;
    } else {
        if (fcntl(fd, F_SETFL, flags & ~O_NONBLOCK) < 0){
            *err_msg = strerror(errno);        
            return 0;
        } else {
            return 1;
        }
    }
}

bool race_set_rcv_timeout (int fd, int millis, const char** err_msg) {
    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = millis * 1000;
    if (setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&tv,sizeof(tv)) < 0){
        *err_msg = strerror(errno);
        return 0;
    } else {
        return 1;
    }
}

int race_check_available (int fd, const char** err_msg) {
    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 0;

    fd_set readfds; 
    FD_ZERO(&readfds);
    FD_SET(fd, &readfds);

    int rv = select(fd + 1, &readfds, NULL, NULL, &tv);
    if (rv == -1) { // error
        *err_msg = strerror(errno);
        return 0;
    } else if (rv == 0) { // nothing to read
        *err_msg = NULL;
        return 0;
    } else {  // we can read without blocking
        return 1;
    }
}
