package sim;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseWheelEvent;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

// TODO reminder for blog post throughout

/* TODO:
 * - clean up code so that the NYC implementation of the simulator lives outside the simulator
 * - set up better logging (Logger class with verbosity levels?)
 * - show the lines on the screen first
 * 	- then work on getting the trains to actually travel along them (but this can be lower priority than pathfinding)
 * - pathfinding to enable travelling citizens
 * - transfers to nearby stops built into pathfinding
 * - train capacities and amount of citizens waiting at stops
 * - click-to-spawn citizens + random / proportional citizen generation based on density maps (+ time-of-day?)
 * - better documentation
 * - time-based pathfinding (not just distance-based) ??
 * - proper private/protected/public levels for all classes ??
 * - speed up/slow down simulation ??
 * - simulation statistics (+ graphing ??) ??
 * - zoop to mouse (work out math??)
 * - map rotation ??
 */

public class Simulator {

	public static void main(String [] args) {

		// app initialization
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
	
	int numStops = 473;

	double globalTime;
	static final double TIME_INCREMENT = 0.1;
	
	static final double MAP_X_SCALE = 0.45;
	static final double MAP_Y_SCALE = 0.5;
	
	Vector2 mouseInitialPos;
	Vector2 mouseFinalPos;

	void setup() {

		// mouse wheel zooming
		this.addMouseWheelListener( new MouseAdapter() {
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
            	            		
	    		Drawable.adjustZoom(-e.getWheelRotation() * Drawable.ZOOM_CONST);
            		            	
            }
        });
		
		// initialize zooming and panning variables
		mouseInitialPos = new Vector2(0, 0);
		mouseFinalPos = new Vector2(0, 0);
		
		Drawable.mousePan = new Vector2(0, 0);		
		Drawable.pan = new Vector2(0, 0);
		Drawable.zoom = 1;

		// iterate through stations and add stops to appropriate lines
		Node[] stations = new Node[numStops];
		double[] stationX = new double[numStops];
		double[] stationY = new double[numStops];
		int i = 0;

		HashMap<String, Line> lines = new HashMap<String, Line>();

		try (BufferedReader reader = new BufferedReader(new FileReader("src/sim/stations_data.csv")) ) {

			String line;

			while ((line = reader.readLine()) != null) {

				String[] n = line.split(",");
				String[] stopLines = n[4].split("-");

				for (String str : stopLines) {

					if (!lines.containsKey(str)) {

						lines.put(str, new Line(str));

					}

				}
				
				Node stop = new Node(n[1], new Vector2(), Vector3.black);
				stations[i] = stop;
				stationX[i] = Double.parseDouble(n[2]);
				stationY[i] = Double.parseDouble(n[3]);
				i++;

				for (String str : stopLines) {

					lines.get(str).addStop(stop, 1);

				}

			}

		} catch (IOException e) { assert false; }
		
		normalize(stationX, -this._windowWidthInWorldUnits * MAP_X_SCALE, this._windowWidthInWorldUnits * MAP_X_SCALE);
		normalize(stationY, -this._windowHeightInWorldUnits * MAP_Y_SCALE, this._windowHeightInWorldUnits * MAP_Y_SCALE);
		
		for (int x = 0; x < stationX.length; x++) {
			
			stations[x].pos.x = stationX[x];
			stations[x].pos.y = stationY[x];
			
		}

		// load in configurations for proper stop orders for lines
		HashMap<String, String> lineConfigs = new HashMap<String, String>();
		try (BufferedReader reader = new BufferedReader(new FileReader("src/sim/lines_stations.csv")) ) {

			String line;
			while ((line = reader.readLine()) != null) {

				if (line.indexOf(",") == -1) { continue; }
				String lineID = line.substring(0, line.indexOf(","));
				lines.get(lineID).setColor(new Vector3Mod(line.substring(line.indexOf(",") + 1, line.indexOf(",", line.indexOf(",") + 1))));
				lineConfigs.put(line.substring(0, line.indexOf(",")), line.substring(line.indexOf(",", line.indexOf(",") + 1) + 1));

			}

		} catch (IOException e) { assert false; }

		// add stations to simulation array
		this.nodes = stations;

		// apply line configurations, remove problematic/invalid lines
		ArrayList<String> linesToRemove = new ArrayList<String>();

		for (Line l : lines.values()) {

			for (int x = 0; x < l.stops.length; x += 8) {

				l.addTrain(new Train(x, l, l.getColor(), TIME_INCREMENT));

			}

			try {

				l.rearrangeStops(lineConfigs.get(l.getID()).split(","));

			} catch (Exception e) {

				System.out.println("Deleted invalid line " + l.getID());
				linesToRemove.add(l.getID());

			}

		}

		for (String s : linesToRemove) {

			lines.remove(s);

		}
		
		System.out.println("Generated lines " + lines.keySet());

		// add lines to simulation array
		i = 0;
		this.lines = new Line[lines.keySet().size()];

		for (Line l : lines.values()) {

			this.lines[i++] = l;

		}
		
	}

	void loop() {

		this.globalTime += TIME_INCREMENT;

		// panning and zooming with mouse and keyboard
		if (mousePressed) {
			
			mouseInitialPos = new Vector2(mousePosition).minus(Drawable.mousePan);
			
		} if (mouseHeld) {
			
			Drawable.mousePan = mousePosition.minus(mouseInitialPos);
			Drawable.constrictPan(Drawable.mousePan);
			
		}
		
		if (keyHeld('A')) {

			Drawable.adjustPan(-Drawable.PAN_CONST, 0);

		} if (keyHeld('D')) {

			Drawable.adjustPan(Drawable.PAN_CONST, 0);

		} if (keyHeld('W')) {

			Drawable.adjustPan(0, Drawable.PAN_CONST);

		} if (keyHeld('S')) {

			Drawable.adjustPan(0, -Drawable.PAN_CONST);

		}

		if (keyHeld('=')) {

			Drawable.adjustZoom(Drawable.ZOOM_CONST);
			
		} if (keyHeld('-')) {

			Drawable.adjustZoom(-Drawable.ZOOM_CONST);
			
		}

		if (keyPressed('C')) {

			Drawable.pan = new Vector2(0, 0);
			Drawable.zoom = 1;
			Drawable.mousePan = new Vector2(0, 0);

		}
				
		// draw game objects
		for (Line l : lines) {

			Drawable.drawCircle(this, l.stops[0]);
			for (int i = 1; i < l.stops.length; i++) {

				Drawable.drawCircle(this, l.stops[i], l.getColor());
				Drawable.drawLine(this, l.stops[i-1], l.stops[i], l.getColor());

			}

		}
		
		for (Line l : lines) {
			
			for (Train t : l.trains) {

				t.updatePosAlongLine();
				Drawable.drawCircle(this, t);
				Drawable.drawString(this, t, t.line.getID(), Vector3.black, Train.FONT_SIZE_CONST, Train.FONT_CENTERED);

			}
			
		}

	}
	
	boolean normalize(double[] arr, double min, double max) {
		
		if (arr == null || arr.length == 0) { return false; }
		
		double minInArr = arr[0];
		double maxInArr = arr[0];
		for (double i : arr) {
			
			if (i < minInArr) { minInArr = i; }
			if (i > maxInArr) { maxInArr = i; }
			
		}
		
		double maxMinDiff = max - min;
		double maxMinInArrDiff = maxInArr - minInArr;
		
		if (maxMinInArrDiff + min == 0) { return false; }
		
		for (int i = 0; i < arr.length; i++) {
			
		   arr[i] = maxMinDiff * (arr[i] - minInArr) / (maxMinInArrDiff) + min;
			
		}
		
		return true;
		
	}
	
}

class Drawable {

	final static double ZOOM_CONST = 0.05;
	final static double ZOOM_MAX = 4;
	final static double ZOOM_MIN = 0.1;
	final static double PAN_CONST = 0.25;
	final static double PAN_X_MINMAX = 50;
	final static double PAN_Y_MINMAX = 50;

	static double zoom;
	static Vector2 pan;
	static Vector2 mousePan;

	private String id;
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
	
	public static void adjustPan(double x, double y) {
		
		pan = pan.minus(new Vector2(x, y));
		constrictPan(pan);
		
	}
	
	public static void constrictPan(Vector2 pan) {
		
		pan.x = Math.min(Math.max(pan.x, -PAN_X_MINMAX), PAN_X_MINMAX);
		pan.y = Math.min(Math.max(pan.y, -PAN_Y_MINMAX), PAN_Y_MINMAX);
		
	}
	
	public static void adjustZoom(double z) {
		
		zoom = Math.min(Math.max(zoom+z, ZOOM_MIN), ZOOM_MAX);
		
	}

	public static void drawCircle(App a, Drawable d) {

		a.drawCircle(d.pos.plus(pan).plus(mousePan).times(zoom), d.size * zoom, d.color);

	}
	
	public static void drawCircle(App a, Drawable d, Vector3 col) {

		a.drawCircle(d.pos.plus(pan).plus(mousePan).times(zoom), d.size * zoom, col);

	}

	public static void drawLine(App a, Drawable d1, Drawable d2) {

		a.drawLine(d1.pos.plus(pan).plus(mousePan).times(zoom), d2.pos.plus(pan).plus(mousePan).times(zoom), d2.color);

	}
	
	public static void drawLine(App a, Drawable d1, Drawable d2, Vector3 col) {

		a.drawLine(d1.pos.plus(pan).plus(mousePan).times(zoom), d2.pos.plus(pan).plus(mousePan).times(zoom), col);

	}
	
	public static void drawString(App a, Drawable d, String str, int size, boolean centered) {

		a.drawString(str, d.pos.plus(pan).plus(mousePan).times(zoom), d.color, (int) Math.ceil(size * zoom), centered);

	}

	public static void drawString(App a, Drawable d, String str, Vector3 col, int size, boolean centered) {

		a.drawString(str, d.pos.plus(pan).plus(mousePan).times(zoom), col, (int) Math.ceil(size * zoom), centered);

	}

	public String toString() { return this.id; }
	public String getID() { return this.id; }
	public void setID(String id) { this.id = id; }

}

// TODO
class Citizen extends Drawable {

	public Citizen(Vector2 pos, Vector3 color, double size) {

		super(pos, color, size);

	}

}

class Node extends Drawable {

	private static final double DEFAULT_NODE_SIZE = 0.5;
	
	private double ridership;

	public Node(Vector2 pos, Vector3 color) {

		super(pos, color, DEFAULT_NODE_SIZE);

	}
	
	public Node(Vector2 pos, Vector3 color, double size) {
		
		super(pos, color, size);
		
	}

	public Node(String id, Vector2 pos, Vector3 color) {

		super(id, pos, color, DEFAULT_NODE_SIZE);

	}
	
	public Node(String id, Vector2 pos, Vector3 color, double size) {
		
		super(id, pos, color, size);
		
	}
	
	public void setRidership(double d) { this.ridership = d; }
	public double getRidership() { return this.ridership; }

}

class Train extends Drawable {

	Line line;
	int stop;
	double time;
	double stopTime;
	double stoppedTime;
	double speed;
	
	public static final double DEFAULT_TRAIN_SIZE = 1.0;
	public static final double DEFAULT_STOP_DURATION = 6;
	public static final int FONT_SIZE_CONST = 11;
	public static final boolean FONT_CENTERED = true;

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

			// go to next station
			if (stoppedTime >= DEFAULT_STOP_DURATION) {

				stopTime = 0;
				stoppedTime = 0;
				stop = nextStop;
				this.pos = this.line.stops[stop].pos;

			// wait at station
			} else {

				stoppedTime += speed;

			}

		} else {

			// move along line
			this.pos = Vector2.lerp(stopTime/this.line.dists[nextStop], this.line.stops[stop].pos, this.line.stops[nextStop].pos);

		}

	}

}

class Line {

	private String id;
	private Vector3 color;
	Node[] stops;
	double[] dists;
	Train[] trains;

	public Line(String id) {

		this.id = id;

	}
	
	public Line(String id, Node[] stops, double[] dists, Train[] trains) {
		
		this(id);
		this.stops = stops;
		this.dists = dists;
		this.trains = trains;
		
	}

	public void setStops(Node[] stops, double[] distances) {

		this.stops = stops;
		this.dists = distances;

	}
	
	public void setColor(Vector3 col) { this.color = col; }
	public Vector3 getColor() { return this.color; }

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
	
	// faster bulk stop add operation
	public void addStops(Node[] stops, double[] dists) {
		
		if (this.stops == null) { this.stops = new Node[0]; }
		if (this.dists == null) { this.dists = new double[0]; }
		Node[] newStops = new Node[this.stops.length + stops.length];
		double[] newDists = new double[this.stops.length + dists.length];
		assert this.stops.length == this.dists.length;
		
		for (int i = 0; i < this.stops.length; i++) {

			newStops[i] = this.stops[i];
			newDists[i] = this.dists[i];

		}
		
		for(int i = 0; i < stops.length; i++) {
			
			newStops[i+this.stops.length] = stops[i];
			newDists[i+this.dists.length] = dists[i];
			
		}
		
		this.stops = newStops;
		this.dists = newDists;
		
	}

	// reconfigure stop list according to preset stop pattern
	public void rearrangeStops(String[] stopConfig) {

		ArrayList<Node> newStops = new ArrayList<Node>();
		for (String s : stopConfig) {

			for (Node n : this.stops) {

				if (n.getID().equals(s.replaceAll("_", " "))) {

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

	// faster bulk train add operation
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
	
	public String toString() { return this.id + ": " + Arrays.toString(this.stops); }
	public String getID() { return this.id; }
	public void setID(String id) { this.id = id; }

}

class Vector3Mod extends Vector3 {

	Vector3Mod(String hex) {
		this.x = (double)Integer.parseInt(hex.substring(0, 2), 16)/255.0;
		this.y = (double)Integer.parseInt(hex.substring(2, 4), 16)/255.0;
		this.z = (double)Integer.parseInt(hex.substring(4, 6), 16)/255.0;
	}

}