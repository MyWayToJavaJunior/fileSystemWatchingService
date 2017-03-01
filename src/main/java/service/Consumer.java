package service;

import javafx.util.Pair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Class for transform native event to FileSystemWatchEvent
 * it is necessary to receive RENAME and MOVE events
 * because native events is only CREATE, DELETE and MODIFY
 *
 * Depending on the file system RENAME and MOVE events is DELETE+RENAME native events
 *
 */
public class Consumer implements Runnable{

    //collection for snapshot events
    private CopyOnWriteArrayList<Pair<Path, List<WatchEvent<?>>>> newQueue = new CopyOnWriteArrayList<>();
    //collection for events from WatchService
    private BlockingQueue<Pair<Path, List<WatchEvent<?>>>> queue;
    //collection for processing MOVE, DELETE and CREATE events
    private BlockingQueue<Pair<Path, WatchEvent<?>>> bufferQueue = new LinkedBlockingQueue<>();
    //link to the FileSystemWatchService
    private FileSystemWatchingService service;

    //flag for stopping consumer
    private boolean isAlive = true;

    //delay for sleep consumer
    private final int DELAY = 100;

    /**
     * Constructor takes collection WatchService events and {@link FileSystemWatchingService}
     * @param queue
     * @param service
     */
    Consumer(BlockingQueue<Pair<Path, List<WatchEvent<?>>>> queue, FileSystemWatchingService service) {
        this.queue = queue;
        this.service = service;
    }

    /**
     * consumer starts and after a time DELAY process the event queue
     */
    @Override
    public void run() {
        while (isAlive) {
            try {
                Thread.sleep(DELAY);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            //if queue is not empty start new Thread and process events
            //after DELAY check the queue size and if it is not empty start new Thread
            if (queue.size() > 0) {
                new Thread(() -> {

                    //fixing the set of events
                    newQueue.addAll(queue);
                    queue.removeAll(newQueue);

                    //processing events
                    for (Pair<Path, List<WatchEvent<?>>> pair : newQueue) {

                        //take the event
                        List<WatchEvent<?>> events = pair.getValue();
                        //take the path to element, where event occurs
                        Path dir = pair.getKey();

                        //find RENAME event
                        //if event type is ENTRY_DELETE and next event is ENTRY_CREATE
                        //then RENAME occurs
                        for (int j = 0; j < events.size(); j++) {
                            WatchEvent<?> event = events.get(j);

                            @SuppressWarnings("rawtypes")
                            WatchEvent.Kind kind = event.kind();
                            // Context for directory entry event is the file name of entry
                            @SuppressWarnings("unchecked")
                            Path name = ((WatchEvent<Path>) event).context();
                            Path child = dir.resolve(name);

                            //System.out.format("%s: %s\n", event.kind().name(), child);

                            //if event type is ENTRY_MODIFY run event handler
                            if (((WatchEvent<Path>) event).kind().equals(ENTRY_MODIFY)) {
                                //System.out.println(event.kind());
                                service.runEvents(new FileSystemWatchEvent(FileSystemWatchEvent.Type.MODIFY, child));
                            } else {
                                if (events.size() > 1) {
                                    if (j < events.size() - 1) {
                                        @SuppressWarnings("unchecked")
                                        WatchEvent<Path> nextEvent = (WatchEvent<Path>) events.get(j + 1);
                                        WatchEvent.Kind nextKind = nextEvent.kind();
                                        Path nextName = nextEvent.context();
                                        Path nextChild = dir.resolve(nextName);
                                        if (kind.name().equals("ENTRY_DELETE") && nextKind.name().equals("ENTRY_CREATE") &&
                                                child.getParent().equals(nextChild.getParent())) {
                                            //System.out.println("RENAME from "+child+" to "+nextChild);
                                            service.runEvents(new FileSystemWatchEvent(FileSystemWatchEvent.Type.RENAME, child, nextChild));
                                            service.replaceKey(child, nextChild);
                                            j++;



                                            continue;
                                        }
                                    }
                                    //if the event does not fit the definition RENAME
                                    //add it to bufferQueue for find other events
                                    bufferQueue.add(new Pair<>(dir, event));
                                } else {
                                    bufferQueue.add(new Pair<>(dir, event));
                                }
                            }


                            // if directory is created, and watching recursively, then register it and its sub-directories
                            if (kind == ENTRY_CREATE) {
                                try {
                                    if (Files.isDirectory(child)) {
                                        service.walkAndRegisterDirectories(child);
                                    }
                                } catch (IOException x) {
                                    // do something useful
                                }
                            }
                        }
                    }

                    checkOtherEvents();
                    newQueue.clear();

                }).start();
            }

        }
    }

    /**
     * find MOVE, CREATE, DELETE events
     * bufferQueue processing
     * wen we delete file the fileSystem generate ENTRY_DELETE and ENTRY_CREATE events but not in a strict order
     * therefore we take a event from bufferQueue and fid a pair, the remove these events from queue
     * if we did not find a pair
     */
    private void checkOtherEvents() {
        //collection for prepared events
        List<Pair<Path, WatchEvent<?>>> doneList = new ArrayList<>();
        for (Pair<Path, WatchEvent<?>> p : bufferQueue) {
            if (p != null && !doneList.contains(p)) {
                @SuppressWarnings("unchecked")
                WatchEvent<Path> event = (WatchEvent<Path>) p.getValue();
                Path name = event.context();
                final boolean[] isMoveEvent = {false};

                WatchEvent.Kind<Path> from = event.kind();
                WatchEvent.Kind<Path> to = from.equals(ENTRY_DELETE)?ENTRY_CREATE:ENTRY_DELETE;

                bufferQueue.forEach(e -> {
                    if (!doneList.contains(e)){
                        WatchEvent<Path> ev = (WatchEvent<Path>) e.getValue();
                        Path newName = ev.context();
                        if (ev.kind().equals(to) && newName.equals(name)) {
                            if (from.equals(ENTRY_DELETE)) {
                                //System.out.println("move from " + p.getKey().resolve(name) + " to " + p.getKey().resolve(name));
                                service.runEvents(new FileSystemWatchEvent(FileSystemWatchEvent.Type.MOVE, p.getKey().resolve(name), e.getKey().resolve(name)));
                            }
                            else {
                                //System.out.println("move from " + e.getKey().resolve(newName) + " to " + p.getKey().resolve(name));
                                service.runEvents(new FileSystemWatchEvent(FileSystemWatchEvent.Type.MOVE, e.getKey().resolve(name), p.getKey().resolve(name)));
                            }
                            bufferQueue.remove(e);
                            doneList.add(e);
                            isMoveEvent[0] = true;

                        }
                    }

                });

                if (!isMoveEvent[0]) {
                    //System.out.println(from.toString() + p.getKey().resolve(name));
                    FileSystemWatchEvent.Type eventType = from.equals(ENTRY_CREATE)?FileSystemWatchEvent.Type.CREATE:FileSystemWatchEvent.Type.DELETE;

                    if (from == ENTRY_CREATE) {
                        try {
                            if (Files.isDirectory(p.getKey().resolve(name))) {
                                service.walkAndRegisterDirectories(p.getKey().resolve(name));
                            }
                        } catch (IOException x) {
                            // do something useful
                        }
                    }

                    service.runEvents(new FileSystemWatchEvent(eventType, p.getKey().resolve(name)));
                    isMoveEvent[0] = false;
                }


            }
            bufferQueue.remove(p);
            doneList.add(p);
        }
        //queue.clear();
        bufferQueue.clear();
    }

    /**
     * stop consumer
     */
    public void stop(){
        isAlive = false;
    }
}
