const express = require('express');
const cors = require('cors');

const app = express();
const PORT = process.env.PORT || 3000;

// Middleware
app.use(cors());
app.use(express.json());

// In-memory storage for bus locations
let busLocations = {};

// Vaylen TransitLK API Information
app.get('/', (req, res) => {
    res.json({
        service: 'TransitLK Backend API',
        company: 'Vaylen',
        version: '1.0.0',
        description: 'Real-time bus tracking system for Sri Lanka',
        timestamp: new Date().toISOString(),
        endpoints: {
            health: 'GET /api/health',
            driverLocation: 'POST /api/driver/location',
            driverStop: 'POST /api/driver/location/stop',
            passengerLocation: 'GET /api/passenger/location/:route',
            activeBuses: 'GET /api/buses/active'
        }
    });
});

// Driver updates location
app.post('/api/driver/location', (req, res) => {
    try {
        const { route, lat, lng, driver, timestamp, speed } = req.body;
        
        if (!route || !lat || !lng) {
            return res.status(400).json({ 
                error: 'Missing required fields: route, lat, lng',
                service: 'TransitLK by Vaylen'
            });
        }

        busLocations[route] = { 
            lat: parseFloat(lat), 
            lng: parseFloat(lng), 
            driver: driver || 'Unknown Driver',
            timestamp: timestamp || Date.now(),
            lastUpdated: new Date().toISOString(),
            speed: speed || 0,
            accuracy: req.body.accuracy || 0
        };

        console.log(`🚌 TransitLK (Vaylen) - Driver ${driver} updated location for route ${route}: ${lat}, ${lng}`);
        
        res.json({ 
            success: true, 
            message: 'Location updated successfully',
            service: 'TransitLK by Vaylen',
            location: busLocations[route]
        });
    } catch (error) {
        console.error('Vaylen TransitLK - Error updating location:', error);
        res.status(500).json({ 
            error: 'Internal server error',
            service: 'TransitLK by Vaylen'
        });
    }
});

// Driver stops sharing location
app.post('/api/driver/location/stop', (req, res) => {
    try {
        const { route, driver } = req.body;
        
        if (route && busLocations[route]) {
            delete busLocations[route];
            console.log(`🛑 TransitLK (Vaylen) - Driver ${driver} stopped sharing location for route ${route}`);
        }
        
        res.json({ 
            success: true, 
            message: 'Location sharing stopped',
            service: 'TransitLK by Vaylen'
        });
    } catch (error) {
        console.error('Vaylen TransitLK - Error stopping location sharing:', error);
        res.status(500).json({ 
            error: 'Internal server error',
            service: 'TransitLK by Vaylen'
        });
    }
});

// Passenger gets location for a specific route
app.get('/api/passenger/location/:route', (req, res) => {
    try {
        const route = req.params.route;
        const location = busLocations[route];

        if (location) {
            // Check if location is recent (less than 5 minutes old)
            const isRecent = Date.now() - location.timestamp < 5 * 60 * 1000;
            
            if (isRecent) {
                res.json({
                    ...location,
                    service: 'TransitLK by Vaylen'
                });
            } else {
                // Location is too old, remove it
                delete busLocations[route];
                res.status(404).json({ 
                    error: 'No recent location data available',
                    service: 'TransitLK by Vaylen'
                });
            }
        } else {
            res.status(404).json({ 
                error: 'No active bus found for this route',
                service: 'TransitLK by Vaylen'
            });
        }
    } catch (error) {
        console.error('Vaylen TransitLK - Error getting location:', error);
        res.status(500).json({ 
            error: 'Internal server error',
            service: 'TransitLK by Vaylen'
        });
    }
});

// Get all active buses
app.get('/api/buses/active', (req, res) => {
    try {
        // Filter out old locations
        const now = Date.now();
        Object.keys(busLocations).forEach(route => {
            if (now - busLocations[route].timestamp > 5 * 60 * 1000) {
                delete busLocations[route];
            }
        });

        res.json({
            service: 'TransitLK by Vaylen',
            company: 'Vaylen',
            activeBuses: Object.keys(busLocations).length,
            buses: busLocations,
            timestamp: new Date().toISOString()
        });
    } catch (error) {
        console.error('Vaylen TransitLK - Error getting active buses:', error);
        res.status(500).json({ 
            error: 'Internal server error',
            service: 'TransitLK by Vaylen'
        });
    }
});

// Health check endpoint
app.get('/api/health', (req, res) => {
    res.json({ 
        status: 'OK',
        service: 'TransitLK Backend',
        company: 'Vaylen',
        timestamp: new Date().toISOString(),
        activeBuses: Object.keys(busLocations).length,
        uptime: process.uptime(),
        version: '1.0.0'
    });
});

// Clean up old locations every minute
setInterval(() => {
    const now = Date.now();
    let cleanedCount = 0;
    
    Object.keys(busLocations).forEach(route => {
        if (now - busLocations[route].timestamp > 5 * 60 * 1000) {
            delete busLocations[route];
            cleanedCount++;
        }
    });
    
    if (cleanedCount > 0) {
        console.log(`🧹 Vaylen TransitLK - Cleaned up ${cleanedCount} old bus locations`);
    }
}, 60000);

app.listen(PORT, () => {
    console.log(`\n
    🚀 ██╗   ██╗ █████╗ ██╗   ██╗██╗     ███████╗███╗   ██╗
    📱 ██║   ██║██╔══██╗╚██╗ ██╔╝██║     ██╔════╝████╗  ██║
    🚌 ██║   ██║███████║ ╚████╔╝ ██║     █████╗  ██╔██╗ ██║
    🌐 ╚██╗ ██╔╝██╔══██║  ╚██╔╝  ██║     ██╔══╝  ██║╚██╗██║
    💡  ╚████╔╝ ██║  ██║   ██║   ███████╗███████╗██║ ╚████║
    🔧   ╚═══╝  ╚═╝  ╚═╝   ╚═╝   ╚══════╝╚══════╝╚═╝  ╚═══╝
    `);
    console.log(`\n🚀 Vaylen TransitLK Backend Server Started`);
    console.log(`📍 Port: ${PORT}`);
    console.log(`🏢 Company: Vaylen`);
    console.log(`📱 Product: TransitLK`);
    console.log(`🌐 Base URL: http://localhost:${PORT}`);
    console.log(`❤️  Health: http://localhost:${PORT}/api/health`);
    console.log(`👨‍✈️ Driver API: POST http://localhost:${PORT}/api/driver/location`);
    console.log(`👤 Passenger API: GET http://localhost:${PORT}/api/passenger/location/:route`);
    console.log(`🚌 Active Buses: GET http://localhost:${PORT}/api/buses/active\n`);
});