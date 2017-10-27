NAME=race

SRCDIR=src/main/c
OBJDIR=target/c/obj
BINDIR=target/c/bin
LIBDIR=target/c/lib

os := $(shell uname -s)
arch := $(shell uname -p)

LIBNAME=lib$(NAME)-$(os)-$(arch)

.PHONY: clean splash init_dirs

$(OBJDIR)/%.o: $(SRCDIR)/%.c
	$(CC) $(CFLAGS) $(CPPFLAGS) -c -o $@ $<

all: splash init_dirs $(BINDIR)/testserver $(BINDIR)/testclient $(BINDIR)/test

splash:
	@echo "==== running $(MAKE_COMMAND) in $(PWD)"

init_dirs:
	@mkdir -p $(LIBDIR)
	@mkdir -p $(BINDIR)
	@mkdir -p $(OBJDIR)

clean:
	@echo "==== running $(MAKE_COMMAND) clean in $(PWD)"
	$(RM) $(BINDIR)/*
	$(RM) $(LIBDIR)/*
	$(RM) $(OBJDIR)/*

########################################################################

$(BINDIR)/testserver : $(OBJDIR)/testserver.o $(OBJDIR)/testtrack.o $(LIBDIR)/$(LIBNAME).a
	$(CC) $(LFLAGS) -o $@ $^ -lm -pthread

$(BINDIR)/testclient : $(OBJDIR)/testclient.o $(OBJDIR)/testtrack.o $(LIBDIR)/$(LIBNAME).a
	$(CC) $(LFLAGS) -o $@ $^ -lm

$(BINDIR)/test : $(OBJDIR)/test.o $(LIBDIR)/$(LIBNAME).a
	$(CC) $(LFLAGS) -o $@ $^ -lm


$(LIBDIR)/$(LIBNAME).a : $(addprefix $(OBJDIR)/, local.o connect.o receive.o messages.o databuf.o netutils.o timeutils.o data.o)
	$(AR) -rcs $@ $^ 


$(OBJDIR)/testclient.o : $(addprefix $(SRCDIR)/, testclient.c race.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/testserver.o : $(addprefix $(SRCDIR)/, testserver.c race.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/testtrack.o : $(addprefix $(SRCDIR)/, testtrack.c race.h)
	$(CC) $(CFLAGS) -c -o $@ $<


$(OBJDIR)/local.o : $(addprefix $(SRCDIR)/, local.c race.h race_internal.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/connect.o : $(addprefix $(SRCDIR)/, connect.c race.h race_internal.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/receive.o : $(addprefix $(SRCDIR)/, receive.c race.h race_internal.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/messages.o : $(addprefix $(SRCDIR)/, messages.c race.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/databuf.o : $(addprefix $(SRCDIR)/, databuf.c race.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/timeutils.o : $(addprefix $(SRCDIR)/, timeutils.c)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/netutils.o : $(addprefix $(SRCDIR)/, netutils.c)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/data.o : $(addprefix $(SRCDIR)/, data.c race.h)
	$(CC) $(CFLAGS) -c -o $@ $<

$(OBJDIR)/test.o : $(addprefix $(SRCDIR)/, test.c race.h)
	$(CC) $(CFLAGS) -c -o $@ $<
