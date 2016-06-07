# RACE Log

### 03/23/2016 - XML compression

Investigated potential solutions for better compression of XML archives. Currently,
ASDE-X message archiving takes about 5MB/min using GZIPOutputStreams ('compressed'
Archiver option). Switching to W3C's EXI was actually worse than plain gzip, and using
gzip on a XmlPullParser based homegrown compression (stream dictionary) showed only
a ~10% improvement. Not worth the effort, we just use filters over airports for now,
which brings it down to about 2MB/min

    49758: asdex.xml
     7847: asdex.xml.gz

    12792: asdex.xml.exi
     9718: asdex.xml.exi.gz

    22714: asdex.xmc
     7061: asdex.xmc.gz
