NAME=race

SRCDIR=src/main/c
OBJDIR=target/c/obj
BINDIR=target/c/bin
LIBDIR=target/c/lib

os := $(shell uname -s)
arch := $(shell uname -nid)

LIBNAME=lib$(NAME)-$(os)-$(arch)

.PHONY: clean splash init_dirs

$(OBJDIR)/%.o: $(SRCDIR)/%.c
	$(CC) $(CFLAGS) $(CPPFLAGS) -c -o $@ $<

all: splash init_dirs $(BINDIR)/testserver $(BINDIR)/testclient $(BINDIR)/test $(BINDIR)/test_hmap

splash:
	@echo "==== running $(MAKE_COMMAND) in $(PWD)"

init_dirs:
	@mkdir -nid $(LIBDIR)
	@mkdir -nid $(BINDIR)
	@mkdir -nid $(OBJDIR)

clean:
	@echo "==== running $(MAKE_COMMAND) clean in $(PWD)"
	$(RM) $(BINDIR)/*
	$(RM) $(LIBDIR)/*
	$(RM) $(OBJDIR)/*

########################################################################


# librace

$(LIBDIR)/$(LIBNAME).a : $(addprefix $(OBJDIR)/, race.o messages.o databuf.o netutils.o timeutils.o simpletrack.o hmap.o)
	$(AR) -rcs $@ $^ 


$(OBJDIR)/race.o : $(addprefix $(SRCDIR)/, race.c race.h race_internal.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/messages.o : $(addprefix $(SRCDIR)/, messages.c race.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/databuf.o : $(addprefix $(SRCDIR)/, databuf.c race.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/timeutils.o : $(addprefix $(SRCDIR)/, timeutils.c)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/netutils.o : $(addprefix $(SRCDIR)/, netutils.c)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/simpletrack.o : $(addprefix $(SRCDIR)/, simpletrack.c race.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/hmap.o : $(addprefix $(SRCDIR)/, hmap.c)
	$(CC) $(CFLAGS) -c -o $@ $<

# tools and tests

$(BINDIR)/testserver : $(OBJDIR)/testserver.o $(OBJDIR)/testtrack.o $(LIBDIR)/$(LIBNAME).a
	$(CC) $(LFLAGS) -o $@ $^ -lm -pthread

$(BINDIR)/testclient : $(OBJDIR)/testclient.o $(OBJDIR)/testtrack.o $(LIBDIR)/$(LIBNAME).a
	$(CC) $(LFLAGS) -o $@ $^ -lm

$(BINDIR)/test : $(OBJDIR)/test.o $(LIBDIR)/$(LIBNAME).a
	$(CC) $(LFLAGS) -o $@ $^ -lm

$(BINDIR)/test_hmap : $(OBJDIR)/test_hmap.o $(LIBDIR)/$(LIBNAME).a
	$(CC) $(LFLAGS) -o $@ $^ -lm

$(OBJDIR)/testclient.o : $(addprefix $(SRCDIR)/, test/testclient.c race.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/testserver.o : $(addprefix $(SRCDIR)/, test/testserver.c race.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/testtrack.o : $(addprefix $(SRCDIR)/, test/testtrack.c race.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/test.o : $(addprefix $(SRCDIR)/, test/test.c race.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/test_hmap.o : $(addprefix $(SRCDIR)/, test/test_hmap.c hmap.h)
	$(CC) $(CFLAGS) -c -o $@ $<