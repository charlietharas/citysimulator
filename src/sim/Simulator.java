package sim;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

/* TODO:
 * - CONVERT LINE AND SUBWAY DATA TO CSV
 * - better/clean draw methods for pan/zoom
 * - accurate line data on map using extra dataset
 * - clean up code so that the NYC implementation of the simulator lives outside the simulator
 * - pathfinding to enable travelling citizens
 * - transfers to nearby stops built into pathfinding
 * - stop capacities (for trains) and amount of citizens waiting at stops
 * - click-to-spawn citizens + random / proportional citizen generation based on density maps + time-of-day
 * - documentation
 * - speed up/slow down simulation ??
 * - simulation statistics (+ graphing ??) ??
 */

public class Simulator {

	public static void main(String [] args) {
		
		App app = new Sim();
        app.setWindowBackgroundColor(Vector3.white);
        app.setWindowSizeInWorldUnits(150.0, 180.0);
        app.setWindowCenterInWorldUnits(0.0, 0.0);
        app.setWindowHeightInPixels(1024);
        app.setWindowTopLeftCornerInPixels(64, 64);
        app.run();
		
	}
	
}

class Sim extends App {
    	
	Line[] lines;
	Node[] nodes;
	
	double globalTime;
	final double TIME_INCREMENT = 0.1;
	
	void setup() {
		
		Node[] stations = new Node[473];
		int i = 0;
		
		HashMap<String, Line> lines = new HashMap<String, Line>();
		
		try (BufferedReader reader = new BufferedReader(new FileReader("src/sim/stations.txt")) ) {
            
			String line;
			lines.put("A_L", new Line("A_L"));
			lines.put("A_F", new Line("A_F"));
			
            while ((line = reader.readLine()) != null) {
                
            	String[] n = line.split(" ");
            	String[] stopLines = n[3].split("-");
            	
        		for (String str : stopLines) {
        			
        			if (!lines.containsKey(str)) {
        				
        				lines.put(str, new Line(str));
        				
        			}
        			
        		}
            	
        		Node stop = new Node(n[0].replaceAll("_", " "), new Vector2(Double.parseDouble(n[1]), Double.parseDouble(n[2])), Vector3.black);
            	stations[i++] = stop;
            	
            	for (String str : stopLines) {
            		
            		if (str.equals("A") && !"".contains(stop.id)) {
            			
            			lines.get("A_L").addStop(stop, 1);
            			lines.get("A_F").addStop(stop, 1);
            			continue;
            			
            		}
            		
            		lines.get(str).addStop(stop, 1);
            		
            	}
            	
            }
        
		} catch (IOException e) { assert false; }
		
		lines.remove("A");
		
		HashMap<String, String> lineConfigs = new HashMap<String, String>();
		try (BufferedReader reader = new BufferedReader(new FileReader("src/sim/lines_nodes.txt")) ) {
            
			String line;
            while ((line = reader.readLine()) != null) {
                
            	if (line.indexOf(" ") == -1) continue;
            	lineConfigs.put(line.substring(0, line.indexOf(" ")), line.substring(line.indexOf(" ") + 1));
            	
            }
        
		} catch (IOException e) { assert false; }
		
		this.nodes = stations;
				
		System.out.println(lines.keySet());
		ArrayList<String> linesToRemove = new ArrayList<String>();
		
		for (Line l : lines.values()) {
			
			Vector3 col = new Vector3("808183");
			if ("AA_LA_FCE".contains(l.getID()) && !l.getID().equals("L") && !l.getID().equals("F")) {
				col = new Vector3("0039a6");
			} else if ("BDFM".contains(l.getID())) {
				col = new Vector3("ff6319");
			} else if ("G".contains(l.getID())) {
				col = new Vector3("6cbe45");
			} else if ("L".contains(l.getID())) {
				col = new Vector3("a7a9ac");
			} else if ("JZ".contains(l.getID())) {
				col = new Vector3("996633");
			} else if ("NQRW".contains(l.getID())) {
				col = new Vector3("fccc0a");
			} else if ("123".contains(l.getID())) {
				col = new Vector3("ee352e");
			} else if ("456".contains(l.getID())) {
				col = new Vector3("00933c");
			} else if ("7".contains(l.getID())) {
				col = new Vector3("b933ad");
			}
			
			for (int x = 0; x < l.stops.length; x += 8) {

				l.addTrain(new Train(x, l, col, TIME_INCREMENT));
			
			}
			
			try {
				
				l.rearrangeStops(lineConfigs.get(l.getID()).split(" "));
			
			} catch (Exception e) {
			
				System.out.println("Deleted invalid line " + l.getID());
				linesToRemove.add(l.getID());
				
			}
		
		}
		
		for (String s : linesToRemove) {
			
			lines.remove(s);
			
		}
		
		i = 0;
		this.lines = new Line[lines.keySet().size()];

		for (Line l : lines.values()) {
			
			this.lines[i++] = l;
			
		}
		
	}
	
    void loop() {
        
    	this.globalTime += TIME_INCREMENT;
    	
    	for (Line l : lines) {

    		drawCircle(l.stops[0].pos, l.stops[0].size, l.stops[0].color);
    		for (int i = 1; i < l.stops.length; i++) {
    			
    			Node n = l.stops[i];
    			Node p = l.stops[i-1];
    			drawCircle(n.pos, n.size, n.color);
    			drawLine(p.pos, n.pos, n.color);
    			
    		}
    		
    		for (Train t : l.trains) {
    			
    			t.updatePosAlongLine();
    			drawCircle(t.pos, t.size, t.color);
    			drawString(t.line.getID(), t.pos, Vector3.black, 11, true);
    			
    		}
    		    		
    	}
    	
    }
}

class Drawable {
	
	String id;
	Vector2 pos;
	Vector3 color;
	double size;
	
	public Drawable(Vector2 pos, Vector3 color, double size) {
		
		this.id = "";
		this.pos = pos;
		this.color = color;
		this.size = size;
		
	}
	
	public Drawable(String id, Vector2 pos, Vector3 color, double size) {
		
		this.id = id;
		this.pos = pos;
		this.color = color;
		this.size = size;
		
	}
	
	public String toString() { return this.id; }
	public void setID(String id) { this.id = id; }
	
}

class Citizen extends Drawable {

	public Citizen(Vector2 pos, Vector3 color, double size) {
		
		super(pos, color, size);
	
	}
	
}

class Node extends Drawable {
	
	private static final double DEFAULT_NODE_SIZE = 0.5;
	
	public Node(Vector2 pos, Vector3 color) {
		
		super(pos, color, DEFAULT_NODE_SIZE);
		
	}
	
	public Node(String id, Vector2 pos, Vector3 color) {

		super(id, pos, color, DEFAULT_NODE_SIZE);

	}

}

class Train extends Drawable {
	
	Line line;
	int stop;
	double time;
	double stopTime;
	double stoppedTime;
	double speed;
	private static final double DEFAULT_TRAIN_SIZE = 1.0;
	private static final double DEFAULT_STOP_DURATION = 6;
	
	public Train(int spawnStop, Line line, Vector3 color, double speed) { 
	
		super(line.stops[spawnStop].pos, color, DEFAULT_TRAIN_SIZE);
		this.line = line;
		this.stop = spawnStop;
		this.speed = speed;
		
	}
	
	public void setPos(Vector2 pos) {
		
		this.pos = pos;
		
	}
	
	public void updatePosAlongLine() {
		
		int nextStop = (this.stop+1) % line.stops.length;
		time += speed;
		stopTime += speed;
		
		if (stopTime >= this.line.dists[nextStop]) {
			
			if (stoppedTime >= DEFAULT_STOP_DURATION) {
			
				stopTime = 0;
				stoppedTime = 0;
				stop = nextStop;
				this.pos = this.line.stops[stop].pos;
			
			} else {
				
				stoppedTime += speed;
				
			}
			
		} else {
			
			this.pos = Vector2.lerp(stopTime/this.line.dists[nextStop], this.line.stops[stop].pos, this.line.stops[nextStop].pos);
			
		}
		
	}

}

class Line {
	
	private String id;
	Node[] stops;
	double[] dists;
	Train[] trains;
	
	public Line(String id) {
		
		this.id = id;
		
	}
	
	public String getID() {
		
		return this.id;
		
	}
	
	public void setStops(Node[] stops, double[] distances) {
		
		this.stops = stops;
		this.dists = distances;
		
	}
	
	public void addStop(Node stop, double dist) {
		
		if (this.stops == null) { this.stops = new Node[0]; }
		if (this.dists == null) { this.dists = new double[0]; }
		Node[] newStops = new Node[this.stops.length + 1];
		double[] newDists = new double[this.stops.length + 1];
		assert this.stops.length == this.dists.length;
		for (int i = 0; i < this.stops.length; i++) {
			
			newStops[i] = this.stops[i];
			newDists[i] = this.dists[i];
			
		}
		newStops[newStops.length-1] = stop;
		newDists[newDists.length-1] = dist;
		this.stops = newStops;
		this.dists = newDists;
		
	}

	public void rearrangeStops(String[] stopConfig) {
		
		ArrayList<Node> newStops = new ArrayList<Node>();
		for (String s : stopConfig) {
			
			for (Node n : this.stops) {
								
				if (n.id.equals(s.replaceAll("_", " "))) {
					
					newStops.add(n);
					
				}
				
			}
			
		}
				
		int size = newStops.size();
		for (int i = 1; i < size; i++) {
			
			newStops.add(newStops.get(size-1-i));
			
		}
				
		double[] newDists = new double[newStops.size()];
		for (int i = 0; i < newStops.size()-1; i++) {
			
			newDists[i+1] = Vector2.distanceBetween(newStops.get(i).pos, newStops.get(i+1).pos);
			
		}
		
		Node[] newStopsArray = new Node[newStops.size()];
		for (int i = 0; i < newStopsArray.length; i++) {
			
			newStopsArray[i] = newStops.get(i);
			
		}
		
		this.stops = newStopsArray;
		this.dists = newDists;
		
	}
	
	public void setTrains(Train[] trains) {
		
		this.trains = trains;
		
	}
	
	public void addTrain(Train train) {
		
		if (this.trains == null) { this.trains = new Train[0]; }
		Train[] trainsNew = new Train[this.trains.length + 1];
		for (int i = 0; i < this.trains.length; i++) {
			
			trainsNew[i] = this.trains[i];
			
		}
		trainsNew[trainsNew.length-1] = train;
		this.trains = trainsNew;
		
	}
	
	public void addTrains(Train[] trains) {
		
		if (this.trains == null) { this.trains = new Train[0]; }
		Train[] trainsNew = new Train[this.trains.length + trains.length];
		
		for (int i = 0; i < this.trains.length; i++) {
			
			trainsNew[i] = this.trains[i];
			
		}
		
		for (int i = 0; i < trains.length; i++) {
			
			trainsNew[i+this.trains.length] = trains[i];
			
		}
		
		this.trains = trainsNew;
		
	}
	
}