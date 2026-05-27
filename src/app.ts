import express from 'express'
import { facilitiesRouter } from './routes/facilities'
import { lpsRouter } from './routes/lps'
import { bbRouter } from './routes/bb'
import { reportsRouter } from './routes/reports'
import { errorHandler } from './middleware/errorHandler'

const app = express()

app.use(express.json())

app.get('/health', (_req, res) => res.json({ status: 'ok' }))

app.use('/api/facilities', facilitiesRouter)
app.use('/api/lps', lpsRouter)
app.use('/api/bb', bbRouter)
app.use('/api/reports', reportsRouter)

app.use(errorHandler)

export default app
