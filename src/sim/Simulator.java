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
		App app = new Sim(473, 0.1, new Vector2(150, 180), new Vector2(0.45, 0.5), new Vector2(64, 64), Vector3.white, 1024);

		app.run();

	}

}

class Sim extends App {

	private Line[] lines;
	private Node[] nodes;
	private int numStops;

	private double globalTime;
	private double timeIncrement;

	private double MAP_X_SCALE;
	private double MAP_Y_SCALE;

	private Vector2 mouseInitialPos;

	public Sim(int numStops, double timeIncrement, Vector2 WORLD_SIZE, Vector2 MAP_X_Y_SCALE, Vector2 windowTopLeft, Vector3 backgroundColor, int windowHeight) {

		this.numStops = numStops;
		this.timeIncrement = timeIncrement;
		setWindowSizeInWorldUnits(WORLD_SIZE.x, WORLD_SIZE.y);
		this.MAP_X_SCALE = MAP_X_Y_SCALE.x;
		this.MAP_Y_SCALE = MAP_X_Y_SCALE.y;
		setWindowTopLeftCornerInPixels((int) windowTopLeft.x, (int) windowTopLeft.y);
		setWindowCenterInWorldUnits(0.0, 0.0);
		setWindowHeightInPixels(windowHeight);
		setWindowBackgroundColor(backgroundColor);

	}

	void setup() {

		// mouse wheel zooming
		this.addMouseWheelListener( new MouseAdapter() {
			@Override public void mouseWheelMoved(MouseWheelEvent e) {

				Drawable.adjustZoom(-e.getWheelRotation() * Drawable.ZOOM_CONST);

			}
		});

		// initialize zooming and panning variables
		mouseInitialPos = new Vector2(0, 0);
		Drawable.resetPanZoom();

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

		for (int j = 0; j < stationX.length; j++) {

			stations[j].setPos(stationX[j], stationY[j]);

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

			for (int x = 0; x < l.getLength(); x += 8) {

				l.addTrain(new Train(x, l, l.getColor(), timeIncrement));

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

		this.globalTime += timeIncrement;

		// panning and zooming with mouse and keyboard
		if (mousePressed) {

			mouseInitialPos = new Vector2(mousePosition).minus(Drawable.getMousePan());

		} if (mouseHeld) {

			Drawable.setMousePan(mousePosition.minus(mouseInitialPos));

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

			Drawable.resetPanZoom();

		}

		// draw game objects
		for (Line l : lines) {

			Drawable.drawCircle(this, l.getStop(0));
			for (int i = 1; i < l.getLength(); i++) {

				Drawable.drawCircle(this, l.getStop(i), l.getColor()); // results in unnecessary draw calls, but acceptable
				Drawable.drawLine(this, l.getStop(i-1), l.getStop(i), l.getColor());

			}

		}

		for (Line l : lines) {

			for (Train t : l.getTrains()) {

				t.updatePosAlongLine();
				Drawable.drawCircle(this, t);
				Drawable.drawString(this, t, t.getLine().getID(), Vector3.black, Train.FONT_SIZE_CONST, Train.FONT_CENTERED);

			}

		}

	}

	public static boolean normalize(double[] arr, double min, double max) {

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

	public double getGlobalTime() { return this.globalTime; }
	public String toString() { return "City simulation running for " + globalTime + " ticks."; }

}

class Drawable {

	// considering adding methods to allow these to be changed
	final static double ZOOM_CONST = 0.05;
	final static double ZOOM_MAX = 4;
	final static double ZOOM_MIN = 0.1;
	final static double PAN_CONST = 0.25;
	final static double PAN_X_MINMAX = 50;
	final static double PAN_Y_MINMAX = 50;

	private static double zoom;
	private static Vector2 pan;
	private static Vector2 mousePan;

	private String id;
	private Vector2 pos;
	private Vector3 color;
	private double size;

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

		drawCircle(a, d, d.getColor());

	}

	public static void drawCircle(App a, Drawable d, Vector3 col) {

		a.drawCircle(d.pos.plus(pan).plus(mousePan).times(zoom), d.size * zoom, col);

	}

	public static void drawLine(App a, Drawable d1, Drawable d2) {

		drawLine(a, d1, d2, d2.getColor());

	}

	public static void drawLine(App a, Drawable d1, Drawable d2, Vector3 col) {

		a.drawLine(d1.pos.plus(pan).plus(mousePan).times(zoom), d2.pos.plus(pan).plus(mousePan).times(zoom), col);

	}

	public static void drawComplexLine(App a, ComplexLine cl) {

		drawComplexLine(a, cl, cl.getColor());

	}

	public static void drawComplexLine(App a, ComplexLine cl, Vector3 col) {

		for (int i = 1; i < cl.getNodesSize()-1; i++) {

			drawLine(a, cl.getNodes()[i-1], cl.getNodes()[i], col);

		}

	}

	public static void drawString(App a, Drawable d, String str, int size, boolean centered) {

		drawString(a, d, str, d.getColor(), size, centered);

	}

	public static void drawString(App a, Drawable d, String str, Vector3 col, int size, boolean centered) {

		a.drawString(str, d.pos.plus(pan).plus(mousePan).times(zoom), col, (int) Math.ceil(size * zoom), centered);

	}

	public static void resetPanZoom() { pan = new Vector2(); mousePan = new Vector2(); zoom = 1; }
	public static void setPan(Vector2 p) { constrictPan(p); pan = p; }
	public static void setMousePan(Vector2 mp) { constrictPan(mp); mousePan = mp; }
	public static void setZoom(double z) { zoom = z; }
	public static Vector2 getPan() { return pan; }
	public static Vector2 getMousePan() { return mousePan; }
	public static double getZoom() { return zoom; }

	public void setID(String id) { this.id = id; }
	public void setPos(Vector2 pos) { this.pos = pos; }
	public void setPos(double x, double y) { this.pos.x = x; this.pos.y = y; }
	public void setX(double x) { this.pos.x = x; }
	public void setY(double y) { this.pos.y = y; }
	public void setColor(Vector3 col) { this.color = col; }
	public void setSize(double size) { this.size = size; }
	public String getID() { return this.id; }
	public Vector2 getPos() { return this.pos; }
	public double getX() { return this.pos.x; }
	public double getY() { return this.pos.y; }
	public Vector3 getColor() { return this.color; }
	public double getSize() { return this.size; }
	public String toString() { return "Drawable id=" + this.id + " pos=" + this.pos; }

}

// TODO
class Citizen extends Drawable {

	public Citizen(Vector2 pos, Vector3 color, double size) {

		super(pos, color, size);

	}

}

class Node extends Drawable {

	public static final double DEFAULT_NODE_SIZE = 0.5;

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
	public String toString() { return "Node id=" + this.getID() + " pos=" + this.getPos(); }

}

class ComplexLine extends Drawable {

	private Node[] nodes;
	private double length;

	public ComplexLine(String id, Vector3 col, Node[] nodes, double length) {

		super(id, nodes[0].getPos(), col, -1);
		this.nodes = nodes;
		this.length = length;

	}

	public void setNodes(Node[] nodes) { this.nodes = nodes; }
	public void setLength(double length) { this.length = length; }
	public Node[] getNodes() { return this.nodes; }
	public int getNodesSize() { return nodes.length; }
	public double getLength() { return length; }
	public String toString() { return "ComplexLine id=" + this.getID() + " nodes=" + Arrays.deepToString(nodes); }

}

class Train extends Drawable {

	public static final double DEFAULT_TRAIN_SIZE = 1.0;
	public static final double DEFAULT_STOP_DURATION = 6;
	public static final int FONT_SIZE_CONST = 11;
	public static final boolean FONT_CENTERED = true;
	
	private Line line;
	private int stop;
	private double globalTime;
	private double stopTime;
	private double stoppedTime;
	private double speed;

	public Train(int spawnStop, Line line, Vector3 color, double speed) { 

		super(line.getStop(spawnStop).getPos(), color, DEFAULT_TRAIN_SIZE);
		this.line = line;
		this.stop = spawnStop;
		this.speed = speed;

	}

	public void updatePosAlongLine() {

		int nextStop = (this.stop+1) % line.getLength();
		globalTime += speed;
		stopTime += speed;

		if (stopTime >= this.line.getDist(nextStop)) {

			// go to next station
			if (stoppedTime >= DEFAULT_STOP_DURATION) {

				stopTime = 0;
				stoppedTime = 0;
				stop = nextStop;
				setPos(this.line.getStop(stop).getPos());

				// wait at station
			} else {

				stoppedTime += speed;

			}

		} else {

			// move along line
			setPos(Vector2.lerp(stopTime/this.line.getDist(nextStop), this.line.getStop(stop).getPos(), this.line.getStop(nextStop).getPos()));

		}

	}
	
	public void setSpeed(double speed) { this.speed = speed; }
	public Line getLine() { return this.line; }
	public Node getStop() { return this.line.getStop(this.stop); }
	public int getStopIndex() { return this.stop; }
	public double getGlobalTime() { return this.globalTime; }
	public double getStopTime() { return this.stopTime; }
	public double getStoppedTime() { return this.stoppedTime; }
	public double getSpeed() { return this.speed; }
	public String toString() { return "Train id=" + this.getID() + " Line=" + this.getLine().getID() + " pos=" + this.getPos(); }

}

class Line {

	private String id;
	private Vector3 color;

	private Node[] stops;
	private double[] dists;
	private Train[] trains;

	public Line(String id) {

		this.id = id;

	}

	public Line(String id, Node[] stops, double[] dists, Train[] trains) {

		this(id);
		this.stops = stops;
		this.dists = dists;
		this.trains = trains;

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

			newDists[i+1] = Vector2.distanceBetween(newStops.get(i).getPos(), newStops.get(i+1).getPos());

		}

		Node[] newStopsArray = new Node[newStops.size()];
		for (int i = 0; i < newStopsArray.length; i++) {

			newStopsArray[i] = newStops.get(i);

		}

		this.stops = newStopsArray;
		this.dists = newDists;

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

	public void setStops(Node[] stops, double[] dists) { this.stops = stops; this.dists = dists; }
	public void setTrains(Train[] trains) { this.trains = trains; }
	public void setColor(Vector3 col) { this.color = col; }
	public void setID(String id) { this.id = id; }
	public Node[] getStops() { return this.stops; }
	public double[] getDists() { return this.dists; }
	public Node getStop(int i) { return this.stops[i]; }
	public double getDist(int i) { return this.dists[i]; }
	public int getLength() { return this.stops.length; }
	public Train[] getTrains() { return this.trains; }
	public Vector3 getColor() { return this.color; }
	public String getID() { return this.id; }
	public String toString() { return "Line id= " + this.id + " stops=" + Arrays.toString(this.stops); }

}

class Vector3Mod extends Vector3 {

	Vector3Mod(String hex) {

		this.x = (double)Integer.parseInt(hex.substring(0, 2), 16)/255.0;
		this.y = (double)Integer.parseInt(hex.substring(2, 4), 16)/255.0;
		this.z = (double)Integer.parseInt(hex.substring(4, 6), 16)/255.0;

	}

}