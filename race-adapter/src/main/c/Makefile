# in-situ makefile for librace.a, to be used in projects that copy librace sources
# make sure CFLAGS are exported by parent make, to ensure compatibility with external system

# NOTE - this is NOT the makefile used to build librace from within RACE itself

clean:
	@rm -f librace.a *.o

all: librace.a

librace.a : race.o messages.o databuf.o netutils.o timeutils.o simple_track.o extended_track.o hmap.o
	$(AR) -rcs $@ $^ 


race.o : race.c race.h race_internal.h
	$(CC) $(CFLAGS) -c -o $@ $<

messages.o : messages.c race.h
	$(CC) $(CFLAGS) -c -o $@ $<

databuf.o : databuf.c race.h
	$(CC) $(CFLAGS) -c -o $@ $<

timeutils.o : timeutils.c
	$(CC) $(CFLAGS) -c -o $@ $<

netutils.o : netutils.c
	$(CC) $(CFLAGS) -c -o $@ $<

simple_track.o : simple_track.c race.h
	$(CC) $(CFLAGS) -c -o $@ $<

extended_track.o : extended_track.c race.h
	$(CC) $(CFLAGS) -c -o $@ $<

hmap.o : hmap.c hmap.h
	$(CC) $(CFLAGS) -c -o $@ $<
