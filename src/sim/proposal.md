## Charlie Tharas CS136 Final Project Proposal

**What is the title of my project?**

CitySim

**What data structures will I use? Note: Arrays count.**

Arrays, ArrayLists, HashMaps, Graphs, potentially more!

**What is the game/app that I am proposing? Who or what lives in it? What does it do? How does it feel?**

I can sort of split this into two parts: first, I am proposing a program capable of simulating a transit system within an urbanized environment. The program will be capable of supporting stations, lines, vehicles, and citizens, and can model the flow of people throughout an urbanized area. Citizens will pathfind through the system, entering at appropriate stations and boarding appropriate vehicles, transferring (even exiting/re-entering the system) as necessary to reach their final destination. This will be graphically represented with reasonably prettiness. The challenge of this part will be a) to get it working (doy) and b) to get it working smoothly for as many citizens as possible (ideally on the order of thousands upon thousands).

I will then implement this simulation on the New York City Subway using publicly available geolocation, density, and commuter data. **I would like to use Python to parse some of this stuff, please.** Java would suck for that :(

**Will the viewer/player interact with my project? How so?**

Um, it depends, mostly on this English paper I have to write. Worst-case, no, they will just watch the city go about its day. Hopefully, they will be able to spawn citizens (probably with the map) (probably with specific pathfinding destinations), and also pan, zoom, and maybe rotate the map. Best-case, they will be able to adjust the simulation speed and maybe even add new lines and nodes graphically. Best-case is kinda unrealistic.

**Does Jim think my project is doable? What is my fallback plan if my project ends up being harder/more time-consuming than I expect? What extensions can I do if my project ends up being easier/less time-consuming than I expect?**

I can split it into phases, sure.
* 1 (bare minimum): nodes, lines, and trains all function properly. Limited citizen functionality, perhaps with buggy/very limited pathfinding. 
* 2 (realistic target): citizens are automatically generated and pathfind properly around the system.
* 3 (egotistical (so my actual) target): citizen pathfinding is super good and efficient, and can handle all sorts of fancy edge cases. The user can spawn in citizens in a nice way graphically. The user can pan, zoom, and maybe rotate the map.
* 4 (unlikely but plausible): the whole app is super polished and nice looking, the user can adjust simulation speed, the simulation generates cool statistics, or travel times, etc.
* 5 (unlikely): the user can create their own cities graphically, or adjust existing ones.

**What is the very first thing I will implement? (Drawing "the data" is usually a good first step.)**

Phase 1, getting all the lines and nodes represented properly on the map with trains moving between them.