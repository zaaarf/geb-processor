# Geb! Processor
This is the processor behind [GEB](https://github.com/zaaarf/geb)'s magic.

## The trick
There is no trick, it's just basic metaprogramming.

Suppose that you have a simple event system, with annotated listeners receiving Event objects. To process something like this at runtime, you need to ask every class to subscribe to the bus, then iterate all of its methods to find the annotated ones to call.

That works, of course, but it's not that fast. Ah, if only you knew in advance, such as at compile time, who's going to get called with what... oh, wait, you do.

The processor then writes at compile time direct calls to all subscribers into the events, to take as little time as possible.

## Usage
You can find in-depth instructions about the usage on the [main GEB repo](https://github.com/zaaarf/geb).

### Compiler arguments
The GEB processor also supports the following compiler arguments to alter its behaviour:
- `gebOutputPackage` to specify where the generated event dispatchers should be placed.
  When not specified, they are placed in the same folder as the event they are for.
  It can be useful in cases  where you are relying listening to events from a third-party library.
  In that case, you might not want to generate code in _their_ packages, to avoid collisions.
