#!/usr/bin/env python3


#import sexpdata
import sys
from enum import Enum
from collections import defaultdict

out_dir = "out"
specs = sys.argv[1:]

class State(Enum):
    IN_MODULE = 1
    START = 2
    NOT_IN_MODULE = 3

for spec in specs:
    print(spec)
    with open(spec) as f:
        a = f.read()
        nesting = 0
        i = 0
        start_block = None
        in_comment = False
        in_string = False
        block_markers = []
        comments = []

        while i < len(a):
            if not in_comment and not in_string:
                if a[i:].startswith("(") and nesting == 0:
                    start_block = i
                    nesting = 1
                elif a[i] == '(':
                    nesting = nesting + 1
                elif a[i] == ')' and nesting > 0:
                    nesting = nesting - 1
                    if nesting == 0:
                        block_markers.append((start_block, i))
                elif a[i:].startswith(";;"):
                    comment_start = i
                    in_comment = True

            if in_comment and a[i] == '\n':
                comments.append((comment_start, i))
                in_comment = False

            if a[i] == '"' and not in_comment:
                in_string = not in_string

            i = i + 1

        # for (s,e) in comments:
        #     thing = a[s:e]
        #     print("$$$$$$$$$$$$$$$$$")
        #     print(thing)

        interesting_module_blocks = []
        queries_for_module = []

        last_module = None

        for (s,e) in block_markers:
            thing = a[s:e+1]
            if thing.startswith("(module"):
                last_module = (s,e)
                continue
            if thing.startswith("(assert_trap") or thing.startswith("(assert_return") or thing.startswith("(invoke"):
                if last_module != None:
                    interesting_module_blocks.append(last_module)
                    last_module = None
                    queries_for_module.append([])
                queries_for_module[-1].append(thing)

        mi = 0

        for ((s,e),q) in zip(interesting_module_blocks,queries_for_module):
            thing = a[s:e+1]
            if thing.startswith("(module"):
                ft = spec.split("/")[-1][:-5] + "-{:03d}".format(mi)
                with open(out_dir + "/" + ft + ".wat", "w") as ff:
                    ff.write(thing)
                with open(out_dir + "/" + ft + ".spec", "w") as ff:
                    ff.write("\n".join(q) + "\n")
                mi = mi + 1
                continue
            if thing.startswith("(assert_trap"):
                continue
            if thing.startswith("(assert_return"):
                continue
            if thing.startswith("(assert_invalid"):
                continue
            if thing.startswith("(assert_malformed"):
                continue
            if thing.startswith("(assert_unlinkable"):
                continue
            if thing.startswith("(assert_exhaustion"):
                continue
            if thing.startswith("(;"):
                continue
        





    
