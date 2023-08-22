# Geb!
GEB (short for _Generative Event Bus_) is an event bus leveraging annotation processing to achieve maximum possible speed.

## The trick
There is no trick, it's just basic metaprogramming.

Suppose that you have a simple event system, with annotated listeners receiving Event objects. To process something like this at runtime, you need to ask every class to subscribe to the bus, then iterate all of its methods to find the annotated ones to call.

That works, of course, but it's not that fast. Ah, if only you knew in advance, such as at compile time, who's going to get called with what... oh, wait, you do.

GEB is just a basic event bus in itself; the actual magician is the processor, who writes into each event direct calls to all subscribers, to take as little time as possible.

## What's with the name?
"GEB Bus" kind of sounds like "Jeb Bush" and I think it's very funny. Please clap.